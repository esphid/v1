idf_component_register(SRCS "proto.c" "json.c" "layout.c" "safety.c" INCLUDE_DIRS "include" REQUIRES cjson)
if(CMAKE_BUILD_EARLY_EXPANSION)
  return()
endif()
