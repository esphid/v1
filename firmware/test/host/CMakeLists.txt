cmake_minimum_required(VERSION 3.16)
project(esphid_host C)
set(CMAKE_C_STANDARD 11)
set(PROTO ../../components/esphid_proto)
set(CJSON ../../managed_components/espressif__cjson/cJSON)
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${CJSON}/cJSON.c")
  # No IDF build has populated managed_components (e.g. CI): fetch the same cJSON release.
  # Only the two source files are used. cJSON's own CMakeLists.txt declares a CMake
  # minimum below 3.5, which CMake 4 refuses, so SOURCE_SUBDIR points at a directory that
  # does not exist and FetchContent only downloads.
  if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
  endif()
  include(FetchContent)
  FetchContent_Declare(cjson
    URL https://github.com/DaveGamble/cJSON/archive/refs/tags/v1.7.19.tar.gz
    URL_HASH SHA256=7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562
    SOURCE_SUBDIR sources-only)
  FetchContent_MakeAvailable(cjson)
  set(CJSON ${cjson_SOURCE_DIR})
endif()
add_library(cjson STATIC ${CJSON}/cJSON.c)
target_include_directories(cjson PUBLIC ${CJSON})
target_compile_definitions(cjson PRIVATE CJSON_NESTING_LIMIT=16 CJSON_CIRCULAR_LIMIT=32)
add_executable(proto_test test.c ${PROTO}/proto.c ${PROTO}/json.c ${PROTO}/layout.c ${PROTO}/safety.c)
target_include_directories(proto_test PRIVATE ${PROTO}/include)
target_link_libraries(proto_test PRIVATE cjson m)
target_compile_options(proto_test PRIVATE -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer)
target_link_options(proto_test PRIVATE -fsanitize=address,undefined)
enable_testing()
add_test(NAME protocol COMMAND proto_test)
