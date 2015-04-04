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

import java.io.IOException;

public interface RecordWriter<K, V> {
  /**
   * Writes a key/value pair.
   * 
   * @param key the key to write.
   * @param value the value to write.
   * @throws IOException
   */
  void write(K key, V value) throws IOException;

  /**
   * Close this <code>RecordWriter</code> to future operations.
   * 
   * @throws IOException
   */
  void close() throws IOException;
}