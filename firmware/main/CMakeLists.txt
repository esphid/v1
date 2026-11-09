idf_component_register(SRCS "app_main.c" "hid.c" "config.c" "rpc.c" "net.c" "http.c" "ble.c" "mqtt.c" "ota.c" "tailscale.c" "tls.c"
  INCLUDE_DIRS "."
  REQUIRES esphid_proto esp_tinyusb mdns cjson mqtt led_strip microlink esp_wifi esp_http_server esp_https_server esp_https_ota app_update nvs_flash bt esp_timer esp_driver_gpio mbedtls)
if(CMAKE_BUILD_EARLY_EXPANSION)
  return()
endif()
set(WEB_GZ "${CMAKE_CURRENT_BINARY_DIR}/index.html.gz")
add_custom_command(OUTPUT "${WEB_GZ}"
  COMMAND sh -c "gzip -9 -n -c '${CMAKE_CURRENT_SOURCE_DIR}/web/index.html' > '${WEB_GZ}'"
  DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/web/index.html" VERBATIM)
add_custom_target(esphid_web DEPENDS "${WEB_GZ}")
add_dependencies(${COMPONENT_LIB} esphid_web)
target_add_binary_data(${COMPONENT_LIB} "${WEB_GZ}" BINARY)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Wno-unused-parameter)
