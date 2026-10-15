# Included via CMAKE_PROJECT_KinesisVideoProducerC_INCLUDE, i.e. right after
# producer-c's project() call and before it fetches PIC.
#
# producer-sdk-cpp pins producer-c to a release tag whose PIC pin is a fixed
# version with no override variable, so the only way to build this canary
# against PIC develop is to rewrite the GIT_TAG in the fetched producer-c
# source before its PIC fetch runs. CMAKE_CURRENT_SOURCE_DIR here is the
# producer-c source directory.

set(_pic_dep_file "${CMAKE_CURRENT_SOURCE_DIR}/CMake/Dependencies/libkvspic-CMakeLists.txt")
if(NOT EXISTS "${_pic_dep_file}")
  message(FATAL_ERROR "pin-pic-develop: ${_pic_dep_file} not found; producer-c layout changed")
endif()

file(READ "${_pic_dep_file}" _pic_dep_contents)
string(REGEX REPLACE
       "(GIT_TAG[ \t]+)[^ \t\r\n]+"
       "\\1develop"
       _pic_dep_patched "${_pic_dep_contents}")

if(NOT _pic_dep_patched MATCHES "GIT_TAG[ \t]+develop")
  message(FATAL_ERROR "pin-pic-develop: could not find a GIT_TAG line to rewrite in ${_pic_dep_file}")
endif()

if(NOT _pic_dep_patched STREQUAL _pic_dep_contents)
  file(WRITE "${_pic_dep_file}" "${_pic_dep_patched}")
endif()
message(STATUS "pin-pic-develop: producer-c at ${CMAKE_CURRENT_SOURCE_DIR} will fetch PIC develop")
