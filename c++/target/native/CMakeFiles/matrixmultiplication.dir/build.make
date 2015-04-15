# CMAKE generated file: DO NOT EDIT!
# Generated by "Unix Makefiles" Generator, CMake Version 3.1

#=============================================================================
# Special targets provided by cmake.

# Disable implicit rules so canonical targets will work.
.SUFFIXES:

# Remove some rules from gmake that .SUFFIXES does not remove.
SUFFIXES =

.SUFFIXES: .hpux_make_needs_suffix_list

# Suppress display of executed commands.
$(VERBOSE).SILENT:

# A target that is always out of date.
cmake_force:
.PHONY : cmake_force

#=============================================================================
# Set environment variables for the build.

# The shell in which to execute make rules.
SHELL = /bin/sh

# The CMake executable.
CMAKE_COMMAND = /usr/local/Cellar/cmake/3.1.0/bin/cmake

# The command to remove a file.
RM = /usr/local/Cellar/cmake/3.1.0/bin/cmake -E remove -f

# Escaping for special characters.
EQUALS = =

# The top-level source directory on which CMake was run.
CMAKE_SOURCE_DIR = /usr/local/hama/c++/src

# The top-level build directory on which CMake was run.
CMAKE_BINARY_DIR = /usr/local/hama/c++/target/native

# Include any dependencies generated for this target.
include CMakeFiles/matrixmultiplication.dir/depend.make

# Include the progress variables for this target.
include CMakeFiles/matrixmultiplication.dir/progress.make

# Include the compile flags for this target's objects.
include CMakeFiles/matrixmultiplication.dir/flags.make

CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o: CMakeFiles/matrixmultiplication.dir/flags.make
CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o: /usr/local/hama/c++/src/main/native/examples/impl/matrixmultiplication.cc
	$(CMAKE_COMMAND) -E cmake_progress_report /usr/local/hama/c++/target/native/CMakeFiles $(CMAKE_PROGRESS_1)
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --green "Building CXX object CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o"
	/usr/bin/c++   $(CXX_DEFINES) $(CXX_FLAGS) -o CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o -c /usr/local/hama/c++/src/main/native/examples/impl/matrixmultiplication.cc

CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.i: cmake_force
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --green "Preprocessing CXX source to CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.i"
	/usr/bin/c++  $(CXX_DEFINES) $(CXX_FLAGS) -E /usr/local/hama/c++/src/main/native/examples/impl/matrixmultiplication.cc > CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.i

CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.s: cmake_force
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --green "Compiling CXX source to assembly CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.s"
	/usr/bin/c++  $(CXX_DEFINES) $(CXX_FLAGS) -S /usr/local/hama/c++/src/main/native/examples/impl/matrixmultiplication.cc -o CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.s

CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.requires:
.PHONY : CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.requires

CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.provides: CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.requires
	$(MAKE) -f CMakeFiles/matrixmultiplication.dir/build.make CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.provides.build
.PHONY : CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.provides

CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.provides.build: CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o

# Object files for target matrixmultiplication
matrixmultiplication_OBJECTS = \
"CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o"

# External object files for target matrixmultiplication
matrixmultiplication_EXTERNAL_OBJECTS =

examples/matrixmultiplication: CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o
examples/matrixmultiplication: CMakeFiles/matrixmultiplication.dir/build.make
examples/matrixmultiplication: libDenseDoubleVector.a
examples/matrixmultiplication: libhadooputils.a
examples/matrixmultiplication: CMakeFiles/matrixmultiplication.dir/link.txt
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --red --bold "Linking CXX executable examples/matrixmultiplication"
	$(CMAKE_COMMAND) -E cmake_link_script CMakeFiles/matrixmultiplication.dir/link.txt --verbose=$(VERBOSE)

# Rule to build all files generated by this target.
CMakeFiles/matrixmultiplication.dir/build: examples/matrixmultiplication
.PHONY : CMakeFiles/matrixmultiplication.dir/build

CMakeFiles/matrixmultiplication.dir/requires: CMakeFiles/matrixmultiplication.dir/main/native/examples/impl/matrixmultiplication.cc.o.requires
.PHONY : CMakeFiles/matrixmultiplication.dir/requires

CMakeFiles/matrixmultiplication.dir/clean:
	$(CMAKE_COMMAND) -P CMakeFiles/matrixmultiplication.dir/cmake_clean.cmake
.PHONY : CMakeFiles/matrixmultiplication.dir/clean

CMakeFiles/matrixmultiplication.dir/depend:
	cd /usr/local/hama/c++/target/native && $(CMAKE_COMMAND) -E cmake_depends "Unix Makefiles" /usr/local/hama/c++/src /usr/local/hama/c++/src /usr/local/hama/c++/target/native /usr/local/hama/c++/target/native /usr/local/hama/c++/target/native/CMakeFiles/matrixmultiplication.dir/DependInfo.cmake --color=$(COLOR)
.PHONY : CMakeFiles/matrixmultiplication.dir/depend

