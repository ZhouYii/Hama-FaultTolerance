/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hama.bsp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hama.Constants;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.ipc.HamaRPCProtocolVersion;
import org.apache.hama.ipc.JobSubmissionProtocol;
import org.apache.hama.ipc.RPC;

/**
 * BSPJobClient is the primary interface for the user-job to interact with the
 * BSPMaster.
 * 
 * BSPJobClient provides facilities to submit jobs, track their progress, access
 * component-tasks' reports/logs, get the BSP cluster status information etc.
 */
public class BSPJobClient extends Configured implements Tool {

  private static final Log LOG = LogFactory.getLog(BSPJobClient.class);

  public static enum TaskStatusFilter {
    NONE, KILLED, FAILED, SUCCEEDED, ALL
  }

  private static final long MAX_JOBPROFILE_AGE = 1000 * 2;
  // job files are world-wide readable and owner writable
  final private static FsPermission JOB_FILE_PERMISSION = FsPermission
      .createImmutable((short) 0644); // rw-r--r--
  // job submission directory is world readable/writable/executable
  final static FsPermission JOB_DIR_PERMISSION = FsPermission
      .createImmutable((short) 0777); // rwx-rwx-rwx

  private JobSubmissionProtocol jobSubmitClient = null;
  private Path sysDir = null;
  private FileSystem fs = null;

  class NetworkedJob implements RunningJob {
    JobProfile profile;
    JobStatus status;
    long statustime;

    public NetworkedJob() {
    }

    public NetworkedJob(JobStatus job) throws IOException {
      this.status = job;
      this.profile = jobSubmitClient.getJobProfile(job.getJobID());
      this.statustime = System.currentTimeMillis();
    }

    /**
     * Some methods rely on having a recent job profile object. Refresh it, if
     * necessary
     */
    synchronized void ensureFreshStatus() throws IOException {
      if (System.currentTimeMillis() - statustime > MAX_JOBPROFILE_AGE) {
        updateStatus();
      }
    }

    /**
     * Some methods need to update status immediately. So, refresh immediately
     * 
     * @throws IOException
     */
    synchronized void updateStatus() throws IOException {
      this.status = jobSubmitClient.getJobStatus(profile.getJobID());
      this.statustime = System.currentTimeMillis();
    }

    @Override
    public BSPJobID getID() {
      return profile.getJobID();
    }

    @Override
    public String getJobName() {
      return profile.getJobName();
    }

    @Override
    public String getJobFile() {
      return profile.getJobFile();
    }

    @Override
    public long progress() throws IOException {
      ensureFreshStatus();
      return status.progress();
    }

    /**
     * Returns immediately whether the whole job is done yet or not.
     */
    @Override
    public synchronized boolean isComplete() throws IOException {
      updateStatus();
      return (status.getRunState() == JobStatus.SUCCEEDED
          || status.getRunState() == JobStatus.FAILED || status.getRunState() == JobStatus.KILLED);
    }

    /**
     * True if job completed successfully.
     */
    @Override
    public synchronized boolean isSuccessful() throws IOException {
      updateStatus();
      return status.getRunState() == JobStatus.SUCCEEDED;
    }

    @Override
    public synchronized long getSuperstepCount() throws IOException {
      ensureFreshStatus();
      return status.getSuperstepCount();
    }

    /**
     * Blocks until the job is finished
     */
    @Override
    public void waitForCompletion() throws IOException {
      while (!isComplete()) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException ie) {
        }
      }
    }

    /**
     * Tells the service to get the state of the current job.
     */
    @Override
    public synchronized int getJobState() throws IOException {
      updateStatus();
      return status.getRunState();
    }

    @Override
    public JobStatus getStatus() {
      return status;
    }

    /**
     * Tells the service to terminate the current job.
     */
    @Override
    public synchronized void killJob() throws IOException {
      jobSubmitClient.killJob(getID());
    }

    @Override
    public void killTask(TaskAttemptID taskId, boolean shouldFail)
        throws IOException {
      jobSubmitClient.killTask(taskId, shouldFail);
    }

    @Override
    public TaskCompletionEvent[] getTaskCompletionEvents(int startFrom) {
      return jobSubmitClient.getTaskCompletionEvents(getID(), startFrom, 10);
    }
  }

  public BSPJobClient(Configuration conf) throws IOException {
    setConf(conf);
    init(conf);
  }

  public BSPJobClient() {
  }

  public void init(Configuration conf) throws IOException {
    String masterAdress = conf.get("bsp.master.address");
    if (masterAdress != null && !masterAdress.equals("local")) {
      this.jobSubmitClient = (JobSubmissionProtocol) RPC.getProxy(
          JobSubmissionProtocol.class, HamaRPCProtocolVersion.versionID,
          BSPMaster.getAddress(conf), conf,
          NetUtils.getSocketFactory(conf, JobSubmissionProtocol.class));
    } else {
      LOG.debug("Using local BSP runner.");
      this.jobSubmitClient = new LocalBSPRunner(conf);
    }
  }

  /**
   * Close the <code>JobClient</code>.
   */
  public synchronized void close() throws IOException {
    String masterAdress = this.getConf().get("bsp.master.address");
    if (masterAdress != null && !masterAdress.equals("local")) {
      RPC.stopProxy(jobSubmitClient);
    }
  }

  /**
   * Get a filesystem handle. We need this to prepare jobs for submission to the
   * BSP system.
   * 
   * @return the filesystem handle.
   */
  public synchronized FileSystem getFs() throws IOException {
    if (this.fs == null) {
      Path sysDir = getSystemDir();
      this.fs = sysDir.getFileSystem(getConf());
    }
    return fs;
  }

  /**
   * Gets the jobs that are submitted.
   * 
   * @return array of {@link JobStatus} for the submitted jobs.
   * @throws IOException
   */
  public JobStatus[] getAllJobs() throws IOException {
    return jobSubmitClient.getAllJobs();
  }

  /**
   * Gets the jobs that are not completed and not failed.
   * 
   * @return array of {@link JobStatus} for the running/to-be-run jobs.
   * @throws IOException
   */
  public JobStatus[] jobsToComplete() throws IOException {
    return jobSubmitClient.jobsToComplete();
  }

  /**
   * Submit a job to the BSP system. This returns a handle to the
   * {@link RunningJob} which can be used to track the running-job.
   * 
   * @param job the job configuration.
   * @return a handle to the {@link RunningJob} which can be used to track the
   *         running-job.
   * @throws FileNotFoundException
   * @throws IOException
   */
  public RunningJob submitJob(BSPJob job) throws FileNotFoundException,
      IOException {
    return submitJobInternal(job, jobSubmitClient.getNewJobId());
  }

  static Random r = new Random();

  public RunningJob submitJobInternal(BSPJob pJob, BSPJobID jobId)
      throws IOException {
    BSPJob job = pJob;
    job.setJobID(jobId);

    int maxTasks;
    if (job.getConfiguration().getBoolean("hama.yarn.application", false)) {
      int maxMem = job.getConfiguration().getInt("yarn.nodemanager.resource.memory-mb", 0);
      int minAllocationMem = job.getConfiguration().getInt("yarn.scheduler.minimum-allocation-mb", 1024);
      maxTasks = maxMem / minAllocationMem;
    } else {
      ClusterStatus clusterStatus = getClusterStatus(true);
      maxTasks = job.getConfiguration().getInt(Constants.MAX_TASKS_PER_JOB,
          clusterStatus.getMaxTasks() - clusterStatus.getTasks());

      if (maxTasks < job.getNumBspTask()) {
        LOG.warn("The configured number of tasks has exceeded the maximum allowed. Job will run with "
            + maxTasks + " tasks.");
        job.setNumBspTask(maxTasks);
      }
    }

    Path submitJobDir = new Path(getSystemDir(), "submit_"
        + Integer.toString(Math.abs(r.nextInt()), 36));
    Path submitSplitFile = new Path(submitJobDir, "job.split");
    Path submitJarFile = new Path(submitJobDir, "job.jar");
    Path submitJobFile = new Path(submitJobDir, "job.xml");
    LOG.debug("BSPJobClient.submitJobDir: " + submitJobDir);

    FileSystem fs = getFs();
    // Create a number of filenames in the BSPMaster's fs namespace
    fs.delete(submitJobDir, true);
    submitJobDir = fs.makeQualified(submitJobDir);
    submitJobDir = new Path(submitJobDir.toUri().getPath());
    FsPermission bspSysPerms = new FsPermission(JOB_DIR_PERMISSION);
    FileSystem.mkdirs(fs, submitJobDir, bspSysPerms);
    fs.mkdirs(submitJobDir);
    short replication = (short) job.getInt("bsp.submit.replication", 10);

    // only create the splits if we have an input
    if ((job.get(Constants.JOB_INPUT_DIR) != null)
        || (job.get("bsp.join.expr") != null)) {
      // Create the splits for the job
      LOG.debug("Creating splits at " + fs.makeQualified(submitSplitFile));

      InputSplit[] splits = job.getInputFormat().getSplits(job, maxTasks);

      job = partition(job, splits, maxTasks);
      maxTasks = job.getInt("hama.partition.count", maxTasks);

      if (job.getBoolean("input.has.partitioned", false)) {
        splits = job.getInputFormat().getSplits(job, maxTasks);
      }

      if (maxTasks < splits.length) {
        throw new IOException(
            "Job failed! The number of splits has exceeded the number of max tasks. The number of splits: "
                + splits.length + ", The number of max tasks: " + maxTasks);
      }

      job.setNumBspTask(writeSplits(job, splits, submitSplitFile, maxTasks));
      job.set("bsp.job.split.file", submitSplitFile.toString());
    }

    String originalJarPath = job.getJar();

    if (originalJarPath != null) { // copy jar to BSPMaster's fs
      // use jar name if job is not named.
      if ("".equals(job.getJobName())) {
        job.setJobName(new Path(originalJarPath).getName());
      }
      job.setJar(submitJarFile.toString());
      fs.copyFromLocalFile(new Path(originalJarPath), submitJarFile);

      fs.setReplication(submitJarFile, replication);
      fs.setPermission(submitJarFile, new FsPermission(JOB_FILE_PERMISSION));
    } else {
      LOG.warn("No job jar file set.  User classes may not be found. "
          + "See BSPJob#setJar(String) or check Your jar file.");
    }

    // Set the user's name and working directory
    job.setUser(getUnixUserName());
    job.set("group.name", getUnixUserGroupName(job.getUser()));
    if (job.getWorkingDirectory() == null) {
      job.setWorkingDirectory(fs.getWorkingDirectory());
    }

    // Write job file to BSPMaster's fs
    FSDataOutputStream out = FileSystem.create(fs, submitJobFile,
        new FsPermission(JOB_FILE_PERMISSION));

    try {
      job.writeXml(out);
    } finally {
      out.close();
    }

    return launchJob(jobId, job, submitJobFile, fs);
  }

  protected BSPJob partition(BSPJob job, InputSplit[] splits, int maxTasks)
      throws IOException {
    String inputPath = job.getConfiguration().get(Constants.JOB_INPUT_DIR);

    Path partitionDir = new Path("/tmp/hama-parts/" + job.getJobID() + "/");
    if (fs.exists(partitionDir)) {
      fs.delete(partitionDir, true);
    }

    if (job.get("bsp.partitioning.runner.job") != null) {
      return job;
    }// Early exit for the partitioner job.

    if (inputPath != null) {
      int numSplits = splits.length;
      int numTasks = job.getConfiguration().getInt("bsp.peers.num", 0);
      if (LOG.isDebugEnabled()) {
        LOG.debug(" numTasks = "
            + numTasks
            + " numSplits = "
            + numSplits
            + " enable = "
            + (job.getConfiguration().getBoolean(
                Constants.ENABLE_RUNTIME_PARTITIONING, false)
                + " class = " + job.getConfiguration().get(
                Constants.RUNTIME_PARTITIONING_CLASS)));
      }

      if (numTasks == 0) {
        numTasks = numSplits;
      }

      if (job.getConfiguration().getBoolean(
          Constants.ENABLE_RUNTIME_PARTITIONING, false)
          && job.getConfiguration().get(Constants.RUNTIME_PARTITIONING_CLASS) != null) {

        HamaConfiguration conf = new HamaConfiguration(job.getConfiguration());

        conf.setInt(Constants.RUNTIME_DESIRED_PEERS_COUNT, numTasks);
        if (job.getConfiguration().get(Constants.RUNTIME_PARTITIONING_DIR) != null) {
          conf.set(Constants.RUNTIME_PARTITIONING_DIR, job.getConfiguration()
              .get(Constants.RUNTIME_PARTITIONING_DIR));
        }

        conf.set(Constants.RUNTIME_PARTITIONING_CLASS,
            job.get(Constants.RUNTIME_PARTITIONING_CLASS));
        BSPJob partitioningJob = new BSPJob(conf);
        partitioningJob.setJobName("Runtime partitioning job for "
            + partitioningJob.getJobName());
        LOG.debug("partitioningJob input: "
            + partitioningJob.get(Constants.JOB_INPUT_DIR));
        partitioningJob.setInputFormat(job.getInputFormat().getClass());
        partitioningJob.setInputKeyClass(job.getInputKeyClass());
        partitioningJob.setInputValueClass(job.getInputValueClass());
        partitioningJob.setOutputFormat(NullOutputFormat.class);
        partitioningJob.setOutputKeyClass(NullWritable.class);
        partitioningJob.setOutputValueClass(NullWritable.class);
        partitioningJob.setBspClass(PartitioningRunner.class);
        partitioningJob.set("bsp.partitioning.runner.job", "true");
        partitioningJob.getConfiguration().setBoolean(
            Constants.ENABLE_RUNTIME_PARTITIONING, false);
        partitioningJob.setOutputPath(partitionDir);

        boolean isPartitioned = false;
        try {
          isPartitioned = partitioningJob.waitForCompletion(true);
        } catch (InterruptedException e) {
          LOG.error("Interrupted partitioning run-time.", e);
        } catch (ClassNotFoundException e) {
          LOG.error("Class not found error partitioning run-time.", e);
        }

        if (isPartitioned) {
          if (job.getConfiguration().get(Constants.RUNTIME_PARTITIONING_DIR) != null) {
            job.setInputPath(new Path(conf
                .get(Constants.RUNTIME_PARTITIONING_DIR)));
          } else {
            job.setInputPath(partitionDir);
          }
          job.setBoolean("input.has.partitioned", true);
          job.setInputFormat(NonSplitSequenceFileInputFormat.class);
        } else {
          LOG.error("Error partitioning the input path.");
          throw new IOException("Runtime partition failed for the job.");
        }
      }
    }
    return job;
  }

  protected RunningJob launchJob(BSPJobID jobId, BSPJob job,
      Path submitJobFile, FileSystem fs) throws IOException {
    //
    // Now, actually submit the job (using the submit name)
    //
    JobStatus status = jobSubmitClient.submitJob(jobId, submitJobFile
        .makeQualified(fs).toString());
    if (status != null) {
      return new NetworkedJob(status);
    } else {
      throw new IOException("Could not launch job");
    }
  }

  /**
   * Get the {@link CompressionType} for the output {@link SequenceFile}.
   * 
   * @param job the {@link BSPJob}
   * @return the {@link CompressionType} for the output {@link SequenceFile},
   *         defaulting to {@link CompressionType#RECORD}
   */
  static CompressionType getOutputCompressionType(BSPJob job) {
    String val = job.get("bsp.partitioning.compression.type");
    if (val != null) {
      return CompressionType.valueOf(val);
    } else {
      return CompressionType.NONE;
    }
  }

  /**
   * Get the {@link CompressionCodec} for compressing the job outputs.
   * 
   * @param job the {@link BSPJob} to look in
   * @param defaultValue the {@link CompressionCodec} to return if not set
   * @return the {@link CompressionCodec} to be used to compress the job outputs
   * @throws IllegalArgumentException if the class was specified, but not found
   */
  static Class<? extends CompressionCodec> getOutputCompressorClass(BSPJob job,
      Class<? extends CompressionCodec> defaultValue) {
    Class<? extends CompressionCodec> codecClass = defaultValue;
    Configuration conf = job.getConfiguration();
    String name = conf.get("bsp.partitioning.compression.codec");
    if (name != null) {
      try {
        codecClass = conf.getClassByName(name).asSubclass(
            CompressionCodec.class);
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException("Compression codec " + name
            + " was not found.", e);
      }
    }
    return codecClass;
  }

  private static int writeSplits(BSPJob job, InputSplit[] splits,
      Path submitSplitFile, int maxTasks) throws IOException {
    final DataOutputStream out = writeSplitsFileHeader(job.getConfiguration(),
        submitSplitFile, splits.length);
    try {
      DataOutputBuffer buffer = new DataOutputBuffer();
      RawSplit rawSplit = new RawSplit();
      for (InputSplit split : splits) {

        // set partitionID to rawSplit
        if (split.getClass().getName().equals(FileSplit.class.getName())
            && job.getConfiguration().get(Constants.RUNTIME_PARTITIONING_CLASS) != null
            && job.get("bsp.partitioning.runner.job") == null) {
          LOG.debug(((FileSplit) split).getPath().getName());
          String[] extractPartitionID = ((FileSplit) split).getPath().getName()
              .split("[-]");
          rawSplit.setPartitionID(Integer.parseInt(extractPartitionID[1]));
        }

        rawSplit.setClassName(split.getClass().getName());
        buffer.reset();
        split.write(buffer);
        rawSplit.setDataLength(split.getLength());
        rawSplit.setBytes(buffer.getData(), 0, buffer.getLength());
        rawSplit.setLocations(split.getLocations());
        rawSplit.write(out);
      }
    } finally {
      out.close();
    }
    return splits.length;
  }

  private static final int CURRENT_SPLIT_FILE_VERSION = 0;
  private static final byte[] SPLIT_FILE_HEADER = "SPL".getBytes();

  private static DataOutputStream writeSplitsFileHeader(Configuration conf,
      Path filename, int length) throws IOException {
    // write the splits to a file for the bsp master
    FileSystem fs = filename.getFileSystem(conf);
    FSDataOutputStream out = FileSystem.create(fs, filename, new FsPermission(
        JOB_FILE_PERMISSION));
    out.write(SPLIT_FILE_HEADER);
    WritableUtils.writeVInt(out, CURRENT_SPLIT_FILE_VERSION);
    WritableUtils.writeVInt(out, length);
    return out;
  }

  /**
   * Read a splits file into a list of raw splits
   * 
   * @param in the stream to read from
   * @return the complete list of splits
   * @throws IOException
   */
  static RawSplit[] readSplitFile(DataInput in) throws IOException {
    byte[] header = new byte[SPLIT_FILE_HEADER.length];
    in.readFully(header);
    if (!Arrays.equals(SPLIT_FILE_HEADER, header)) {
      throw new IOException("Invalid header on split file");
    }
    int vers = WritableUtils.readVInt(in);
    if (vers != CURRENT_SPLIT_FILE_VERSION) {
      throw new IOException("Unsupported split version " + vers);
    }
    int len = WritableUtils.readVInt(in);
    RawSplit[] result = new RawSplit[len];
    for (int i = 0; i < len; ++i) {
      RawSplit split = new RawSplit();
      split.readFields(in);
      if (split.getPartitionID() != Integer.MIN_VALUE)
        result[split.getPartitionID()] = split;
      else
        result[i] = split;
    }
    return result;
  }

  /**
   * Monitor a job and print status in real-time as progress is made and tasks
   * fail.
   * 
   * @param job
   * @param info
   * @return true, if job is successful
   * @throws IOException
   * @throws InterruptedException
   */
  public boolean monitorAndPrintJob(BSPJob job, RunningJob info)
      throws IOException, InterruptedException {
    String lastReport = null;
    LOG.info("Running job: " + info.getID());
    int eventCounter = 0;

    while (!job.isComplete()) {
      Thread.sleep(3000);
      long step = job.progress();
      String report = "";

      report = "Current supersteps number: " + step;

      if (!report.equals(lastReport)) {
        LOG.info(report);
        lastReport = report;
      }

      TaskCompletionEvent[] events = info.getTaskCompletionEvents(eventCounter);
      eventCounter += events.length;

      for (TaskCompletionEvent event : events) {
        if (event.getTaskStatus() == TaskCompletionEvent.Status.FAILED) {

          // Displaying the task logs
          displayTaskLogs(event.getTaskAttemptId(), event.getGroomServerInfo());
        }
      }
    }

    if (job.isSuccessful()) {
      LOG.info("The total number of supersteps: " + info.getSuperstepCount());
      info.getStatus()
          .getCounter()
          .incrCounter(JobInProgress.JobCounter.SUPERSTEPS,
              info.getSuperstepCount());
      info.getStatus().getCounter().log(LOG);
    } else {
      LOG.info("Job failed.");
    }

    return job.isSuccessful();
  }

  static String getTaskLogURL(TaskAttemptID taskId, String baseUrl) {
    return (baseUrl + "/tasklog?plaintext=true&taskid=" + taskId);
  }

  private static void displayTaskLogs(TaskAttemptID taskId, String baseUrl)
      throws MalformedURLException {
    // The tasktracker for a 'failed/killed' job might not be around...
    if (baseUrl != null) {
      // Construct the url for the tasklogs
      String taskLogUrl = getTaskLogURL(taskId, baseUrl);

      // Copy tasks's stdout of the JobClient
      getTaskLogs(taskId, new URL(taskLogUrl + "&filter=stdout"), System.out);
    }
  }

  private static void getTaskLogs(TaskAttemptID taskId, URL taskLogUrl,
      OutputStream out) {
    try {
      URLConnection connection = taskLogUrl.openConnection();
      BufferedReader input = new BufferedReader(new InputStreamReader(
          connection.getInputStream()));
      BufferedWriter output = new BufferedWriter(new OutputStreamWriter(out));
      try {
        String logData = null;
        while ((logData = input.readLine()) != null) {
          if (logData.length() > 0) {
            output.write(taskId + ": " + logData + "\n");
            output.flush();
          }
        }
      } finally {
        input.close();
      }
    } catch (IOException ioe) {
      LOG.warn("Error reading task output" + ioe.getMessage());
    }
  }

  /**
   * Grab the bspmaster system directory path where job-specific files are to be
   * placed.
   * 
   * @return the system directory where job-specific files are to be placed.
   */
  public Path getSystemDir() {
    if (sysDir == null) {
      sysDir = new Path(jobSubmitClient.getSystemDir());
    }
    return sysDir;
  }

  public static void runJob(BSPJob job) throws FileNotFoundException,
      IOException {
    BSPJobClient jc = new BSPJobClient(job.getConfiguration());

    if (job.getNumBspTask() == 0
        || job.getNumBspTask() > jc.getClusterStatus(false).getMaxTasks()) {
      job.setNumBspTask(jc.getClusterStatus(false).getMaxTasks());
    }

    RunningJob running = jc.submitJob(job);
    BSPJobID jobId = running.getID();
    LOG.info("Running job: " + jobId.toString());

    while (true) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
      }

      if (running.isComplete()) {
        break;
      }

      running = jc.getJob(jobId);
    }

    if (running.isSuccessful()) {
      LOG.info("Job complete: " + jobId);
      LOG.info("The total number of supersteps: " + running.getSuperstepCount());
      running.getStatus().getCounter().log(LOG);
    } else {
      LOG.info("Job failed.");
    }

    // TODO if error found, kill job
    // running.killJob();
    jc.close();
  }

  /**
   * Get an RunningJob object to track an ongoing job. Returns null if the id
   * does not correspond to any known job.
   * 
   * @throws IOException
   */
  private RunningJob getJob(BSPJobID jobId) throws IOException {
    JobStatus status = jobSubmitClient.getJobStatus(jobId);
    if (status != null) {
      return new NetworkedJob(status);
    } else {
      return null;
    }
  }

  /**
   * Get status information about the BSP cluster
   * 
   * @param detailed if true then get a detailed status including the
   *          groomserver names
   * 
   * @return the status information about the BSP cluster as an object of
   *         {@link ClusterStatus}.
   * 
   * @throws IOException
   */
  public ClusterStatus getClusterStatus(boolean detailed) throws IOException {
    return jobSubmitClient.getClusterStatus(detailed);
  }

  // for the testcase
  JobSubmissionProtocol getJobSubmissionProtocol() {
    return jobSubmitClient;
  }

  @Override
  public int run(String[] args) throws Exception {
    int exitCode = -1;
    if (args.length < 1) {
      displayUsage("");
      return exitCode;
    }

    // process arguments
    String cmd = args[0];
    boolean listJobs = false;
    boolean listAllJobs = false;
    boolean listActiveGrooms = false;
    boolean killJob = false;
    boolean submitJob = false;
    boolean getStatus = false;
    String submitJobFile = null;
    String jobid = null;

    HamaConfiguration conf = new HamaConfiguration(getConf());
    init(conf);

    if ("-list".equals(cmd)) {
      if (args.length != 1 && !(args.length == 2 && "all".equals(args[1]))) {
        displayUsage(cmd);
        return exitCode;
      }
      if (args.length == 2 && "all".equals(args[1])) {
        listAllJobs = true;
      } else {
        listJobs = true;
      }
    } else if ("-list-active-grooms".equals(cmd)) {
      if (args.length != 1) {
        displayUsage(cmd);
        return exitCode;
      }
      listActiveGrooms = true;
    } else if ("-submit".equals(cmd)) {
      if (args.length == 1) {
        displayUsage(cmd);
        return exitCode;
      }

      submitJob = true;
      submitJobFile = args[1];
    } else if ("-kill".equals(cmd)) {
      if (args.length == 1) {
        displayUsage(cmd);
        return exitCode;
      }
      killJob = true;
      jobid = args[1];

    } else if ("-status".equals(cmd)) {
      if (args.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = args[1];
      getStatus = true;

      // TODO Later, below functions should be implemented
      // with the Fault Tolerant mechanism.
    } else if ("-list-attempt-ids".equals(cmd)) {
      System.out.println("This function is not implemented yet.");
      return exitCode;
    } else if ("-kill-task".equals(cmd)) {
      System.out.println("This function is not implemented yet.");
      return exitCode;
    } else if ("-fail-task".equals(cmd)) {
      System.out.println("This function is not implemented yet.");
      return exitCode;
    }

    BSPJobClient jc = new BSPJobClient(new HamaConfiguration());
    if (listJobs) {
      listJobs();
      exitCode = 0;
    } else if (listAllJobs) {
      listAllJobs();
      exitCode = 0;
    } else if (listActiveGrooms) {
      listActiveGrooms();
      exitCode = 0;
    } else if (submitJob) {
      HamaConfiguration tConf = new HamaConfiguration(new Path(submitJobFile));
      RunningJob job = jc.submitJob(new BSPJob(tConf));
      System.out.println("Created job " + job.getID().toString());
    } else if (killJob) {
      RunningJob job = jc.getJob(BSPJobID.forName(jobid));
      if (job == null) {
        System.out.println("Could not find job " + jobid);
      } else {
        job.killJob();
        System.out.println("Killed job " + jobid);
      }
      exitCode = 0;
    } else if (getStatus) {
      RunningJob job = jc.getJob(BSPJobID.forName(jobid));
      if (job == null) {
        System.out.println("Could not find job " + jobid);
      } else {
        JobStatus jobStatus = jobSubmitClient.getJobStatus(job.getID());
        System.out.println("Job name: " + job.getJobName());
        System.out.printf("States are:\n\tRunning : 1\tSucceded : 2"
            + "\tFailed : 3\tPrep : 4\n");
        System.out.printf("%s\t%d\t%d\t%s\n", jobStatus.getJobID(),
            jobStatus.getRunState(), jobStatus.getStartTime(),
            jobStatus.getUsername());

        exitCode = 0;
      }
    }

    return 0;
  }

  /**
   * Display usage of the command-line tool and terminate execution
   */
  private static void displayUsage(String cmd) {
    String prefix = "Usage: hama job ";
    String taskStates = "running, completed";
    if ("-submit".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-file>]");
    } else if ("-status".equals(cmd) || "-kill".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id>]");
    } else if ("-list".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " [all]]");
    } else if ("-kill-task".equals(cmd) || "-fail-task".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <task-id>]");
    } else if ("-list-active-grooms".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + "]");
    } else if ("-list-attempt-ids".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id> <task-state>]. "
          + "Valid values for <task-state> are " + taskStates);
    } else {
      System.err.printf(prefix + "<command> <args>\n");
      System.err.printf("\t[-submit <job-file>]\n");
      System.err.printf("\t[-status <job-id>]\n");
      System.err.printf("\t[-kill <job-id>]\n");
      System.err.printf("\t[-list [all]]\n");
      System.err.printf("\t[-list-active-grooms]\n");
      System.err.println("\t[-list-attempt-ids <job-id> " + "<task-state>]\n");
      System.err.printf("\t[-kill-task <task-id>]\n");
      System.err.printf("\t[-fail-task <task-id>]\n\n");
    }
  }

  /**
   * Dump a list of currently running jobs
   * 
   * @throws IOException
   */
  private void listJobs() throws IOException {
    JobStatus[] jobs = jobsToComplete();
    if (jobs == null)
      jobs = new JobStatus[0];

    System.out.printf("%d jobs currently running\n", jobs.length);
    displayJobList(jobs);
  }

  /**
   * Dump a list of all jobs submitted.
   * 
   * @throws IOException
   */
  private void listAllJobs() throws IOException {
    JobStatus[] jobs = getAllJobs();
    if (jobs == null)
      jobs = new JobStatus[0];
    System.out.printf("%d jobs submitted\n", jobs.length);
    System.out.printf("States are:\n\tRunning : 1\tSucceded : 2"
        + "\tFailed : 3\tPrep : 4\n");
    displayJobList(jobs);
  }

  void displayJobList(JobStatus[] jobs) {
    System.out.printf("JobId\tState\tStartTime\tUserName\n");
    for (JobStatus job : jobs) {
      System.out.printf("%s\t%d\t%d\t%s\n", job.getJobID(), job.getRunState(),
          job.getStartTime(), job.getUsername());
    }
  }

  /**
   * Display the list of active groom servers
   */
  private void listActiveGrooms() throws IOException {
    ClusterStatus c = jobSubmitClient.getClusterStatus(true);
    Map<String, String> grooms = c.getActiveGroomNames();
    for (String groomName : grooms.keySet()) {
      System.out.println(groomName);
    }
  }

  /*
   * Helper methods for unix operations
   */

  static String getUnixUserName() throws IOException {
    String[] result = executeShellCommand(new String[] { Shell.USER_NAME_COMMAND });
    if (result.length != 1) {
      throw new IOException("Expect one token as the result of "
          + Shell.USER_NAME_COMMAND + ": " + toString(result));
    }
    String fixResult = fixCygwinName(result[0]);
    return fixResult;
  }

  private static String fixCygwinName(String in) {
    String string = in;
    if (string.contains("\\")) {
      // this is for cygwin systems
      string = string.substring(string.indexOf("\\"));
    }
    return string;
  }

  static String getUnixUserGroupName(String user) throws IOException {
    String[] result = executeShellCommand(new String[] { "bash", "-c",
        "id -Gn " + user });
    if (result.length < 1) {
      throw new IOException("Expect one token as the result of "
          + "bash -c id -Gn " + user + ": " + toString(result));
    }
    return result[0];
  }

  protected static String toString(String[] strArray) {
    if (strArray == null || strArray.length == 0) {
      return "";
    }
    StringBuilder buf = new StringBuilder(strArray[0]);
    for (int i = 1; i < strArray.length; i++) {
      buf.append(' ');
      buf.append(strArray[i]);
    }
    return buf.toString();
  }

  protected static String[] executeShellCommand(String[] command)
      throws IOException {
    String groups = Shell.execCommand(command);
    StringTokenizer tokenizer = new StringTokenizer(groups);
    int numOfTokens = tokenizer.countTokens();
    String[] tokens = new String[numOfTokens];
    for (int i = 0; tokenizer.hasMoreTokens(); i++) {
      tokens[i] = tokenizer.nextToken();
    }

    return tokens;
  }

  public static class RawSplit implements Writable {
    private String splitClass;
    private BytesWritable bytes = new BytesWritable();
    private String[] locations;
    private int partitionID = Integer.MIN_VALUE;
    long dataLength;

    public void setBytes(byte[] data, int offset, int length) {
      bytes.set(data, offset, length);
    }

    public void setClassName(String className) {
      splitClass = className;
    }

    public void setPartitionID(int id) {
      this.partitionID = id;
    }

    public int getPartitionID() {
      return partitionID;
    }

    public String getClassName() {
      return splitClass;
    }

    public BytesWritable getBytes() {
      return bytes;
    }

    public void clearBytes() {
      bytes = null;
    }

    public void setLocations(String[] locations) {
      this.locations = locations;
    }

    public String[] getLocations() {
      return locations;
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      splitClass = Text.readString(in);
      dataLength = in.readLong();
      partitionID = in.readInt();
      bytes.readFields(in);
      int len = WritableUtils.readVInt(in);
      locations = new String[len];
      for (int i = 0; i < len; ++i) {
        locations[i] = Text.readString(in);
      }
    }

    @Override
    public void write(DataOutput out) throws IOException {
      Text.writeString(out, splitClass);
      out.writeLong(dataLength);
      out.writeInt(partitionID);
      bytes.write(out);
      WritableUtils.writeVInt(out, locations.length);
      for (String location : locations) {
        Text.writeString(out, location);
      }
    }

    public long getDataLength() {
      return dataLength;
    }

    public void setDataLength(long l) {
      dataLength = l;
    }

  }

  /**
   */
  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new BSPJobClient(), args);
    System.exit(res);
  }

}
