# build library from source
function(build_dependency lib_name)
  set(supported_libs
      kvsProducerC
      )
  list(FIND supported_libs ${lib_name} index)
  if(${index} EQUAL -1)
    message(WARNING "${lib_name} is not supported for building from source")
    return()
  endif()

  set(lib_file_name ${lib_name})
  set(library_found NOTFOUND)
  find_library(
    library_found
    NAMES ${lib_file_name}
    PATHS ${OPEN_SRC_INSTALL_PREFIX}/lib
    NO_DEFAULT_PATH)
  if(library_found)
    message(STATUS "${lib_name} already built")
    return()
  endif()

  # anything after lib_name(${ARGN}) are assumed to be arguments passed over to
  # library building cmake.
  set(build_args ${ARGN})

  file(REMOVE_RECURSE ${OPEN_SRC_INSTALL_PREFIX}/lib${lib_name})

  # build library
  configure_file(
    ./CMake/Dependencies/lib${lib_name}-CMakeLists.txt
    ${OPEN_SRC_INSTALL_PREFIX}/lib${lib_name}/CMakeLists.txt COPYONLY)
  execute_process(
    COMMAND ${CMAKE_COMMAND} ${build_args}
            -DOPEN_SRC_INSTALL_PREFIX=${OPEN_SRC_INSTALL_PREFIX} -G
            ${CMAKE_GENERATOR} .
    RESULT_VARIABLE result
    WORKING_DIRECTORY ${OPEN_SRC_INSTALL_PREFIX}/lib${lib_name})
  if(result)
    message(FATAL_ERROR "CMake step for lib${lib_name} failed: ${result}")
  endif()
  execute_process(
    COMMAND ${CMAKE_COMMAND} --build .
    RESULT_VARIABLE result
    WORKING_DIRECTORY ${OPEN_SRC_INSTALL_PREFIX}/lib${lib_name})
  if(result)
    message(FATAL_ERROR "CMake step for lib${lib_name} failed: ${result}")
  endif()

  file(REMOVE_RECURSE ${OPEN_SRC_INSTALL_PREFIX}/lib${lib_name})

endfunction()
