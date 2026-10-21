# Selects which source each KVS SDK layer is built from. Shared by canary/producer-c and canary/producer-cpp.
#
#   -DKVS_SDK_TRACK=release   (default) upstream behaviour: top-level SDK from master, nested SDKs at the
#                             release tags pinned inside that SDK's CMake/Dependencies.
#   -DKVS_SDK_TRACK=develop   every layer from its develop branch. PIC and Producer C are fetched here and
#                             handed to the SDK via the LOCAL_KVSPIC_PATH / LOCAL_KVSC_PATH cache entries
#                             that the develop branches honour instead of fetching their pinned tag.
#                             Nothing in the SDK repos is modified.
#
# Per-layer refs (branch, tag or SHA) can be overridden with KVS_PIC_REF, KVS_PRODUCER_C_REF and
# KVS_PRODUCER_CPP_REF; they only apply when KVS_SDK_TRACK=develop.

include(FetchContent)

set(KVS_SDK_TRACK "release" CACHE STRING "Source of the KVS SDK layers: release | develop")
set_property(CACHE KVS_SDK_TRACK PROPERTY STRINGS release develop)
set(KVS_PIC_REF "develop" CACHE STRING "amazon-kinesis-video-streams-pic ref when KVS_SDK_TRACK=develop")
set(KVS_PRODUCER_C_REF "develop" CACHE STRING "amazon-kinesis-video-streams-producer-c ref when KVS_SDK_TRACK=develop")
set(KVS_PRODUCER_CPP_REF "develop" CACHE STRING "amazon-kinesis-video-streams-producer-sdk-cpp ref when KVS_SDK_TRACK=develop")

if(NOT KVS_SDK_TRACK MATCHES "^(release|develop)$")
  message(FATAL_ERROR "KVS_SDK_TRACK must be 'release' or 'develop', got '${KVS_SDK_TRACK}'")
endif()
message(STATUS "KVS SDK track: ${KVS_SDK_TRACK}")

# Ref for the SDK the canary add_subdirectory()s directly: its develop ref on the develop track, else master.
function(kvs_sdk_top_level_ref develop_ref out_var)
  if(KVS_SDK_TRACK STREQUAL "develop")
    set(${out_var} "${develop_ref}" PARENT_SCOPE)
  else()
    set(${out_var} "master" PARENT_SCOPE)
  endif()
endfunction()

# Clone-only fetch; the SDK above it add_subdirectory()s the result through its LOCAL_* path.
function(kvs_sdk_fetch_source name repo ref out_var)
  FetchContent_Declare(${name} GIT_REPOSITORY ${repo} GIT_TAG ${ref} GIT_PROGRESS TRUE)
  FetchContent_GetProperties(${name})
  if(NOT ${name}_POPULATED)
    FetchContent_Populate(${name})
  endif()
  set(${out_var} "${${name}_SOURCE_DIR}" PARENT_SCOPE)
endfunction()

# Call before add_subdirectory() of the top-level SDK. Fetches the nested layers the develop track needs.
#   kvs_sdk_prepare_layers(PIC)             for canary/producer-c  (Producer C is the top level)
#   kvs_sdk_prepare_layers(PIC PRODUCER_C)  for canary/producer-cpp (Producer CPP is the top level)
function(kvs_sdk_prepare_layers)
  if(NOT KVS_SDK_TRACK STREQUAL "develop")
    return()
  endif()
  if("PIC" IN_LIST ARGN)
    kvs_sdk_fetch_source(kvspic_develop https://github.com/awslabs/amazon-kinesis-video-streams-pic.git
                         ${KVS_PIC_REF} pic_dir)
    set(LOCAL_KVSPIC_PATH "${pic_dir}" CACHE PATH "" FORCE)
    set(KVS_SDK_EXPECTED_PIC_DIR "${pic_dir}" PARENT_SCOPE)
  endif()
  if("PRODUCER_C" IN_LIST ARGN)
    kvs_sdk_fetch_source(kvsproducerc_develop https://github.com/awslabs/amazon-kinesis-video-streams-producer-c.git
                         ${KVS_PRODUCER_C_REF} producer_c_dir)
    set(LOCAL_KVSC_PATH "${producer_c_dir}" CACHE PATH "" FORCE)
    set(KVS_SDK_EXPECTED_PRODUCER_C_DIR "${producer_c_dir}" PARENT_SCOPE)
  endif()
endfunction()

function(_kvs_sdk_git_describe dir out_var)
  execute_process(COMMAND git -C "${dir}" log -1 --format=%h\ %cs OUTPUT_VARIABLE out OUTPUT_STRIP_TRAILING_WHITESPACE
                  ERROR_QUIET)
  set(${out_var} "${out}" PARENT_SCOPE)
endfunction()

# Call after add_subdirectory() of the top-level SDK. A branch without LOCAL_* support would ignore the path
# and silently build its release pin, so compare what the SDK actually configured against what was fetched.
function(kvs_sdk_verify_layers)
  _kvs_sdk_git_describe("${pic_project_SOURCE_DIR}" pic_rev)
  _kvs_sdk_git_describe("${KinesisVideoProducerC_SOURCE_DIR}" producer_c_rev)
  message(STATUS "KVS PIC source:        ${pic_project_SOURCE_DIR} (${pic_rev})")
  message(STATUS "KVS Producer C source: ${KinesisVideoProducerC_SOURCE_DIR} (${producer_c_rev})")
  if(DEFINED KinesisVideoProducerCpp_SOURCE_DIR)
    _kvs_sdk_git_describe("${KinesisVideoProducerCpp_SOURCE_DIR}" producer_cpp_rev)
    message(STATUS "KVS Producer CPP source: ${KinesisVideoProducerCpp_SOURCE_DIR} (${producer_cpp_rev})")
  endif()

  if(NOT KVS_SDK_TRACK STREQUAL "develop")
    return()
  endif()
  if(DEFINED KVS_SDK_EXPECTED_PIC_DIR AND NOT pic_project_SOURCE_DIR STREQUAL KVS_SDK_EXPECTED_PIC_DIR)
    message(FATAL_ERROR "KVS_SDK_TRACK=develop but PIC was built from ${pic_project_SOURCE_DIR}, not the fetched "
                        "${KVS_SDK_EXPECTED_PIC_DIR}. The Producer C ref in use does not honour LOCAL_KVSPIC_PATH.")
  endif()
  if(DEFINED KVS_SDK_EXPECTED_PRODUCER_C_DIR AND NOT KinesisVideoProducerC_SOURCE_DIR STREQUAL KVS_SDK_EXPECTED_PRODUCER_C_DIR)
    message(FATAL_ERROR "KVS_SDK_TRACK=develop but Producer C was built from ${KinesisVideoProducerC_SOURCE_DIR}, not the "
                        "fetched ${KVS_SDK_EXPECTED_PRODUCER_C_DIR}. The Producer CPP ref in use does not honour LOCAL_KVSC_PATH.")
  endif()
endfunction()
