cmake_minimum_required(VERSION 3.22.1)
project(kvsocket C)

add_library(kvsocket SHARED kvsocket.c)
find_library(log-lib log)
target_link_libraries(kvsocket ${log-lib})
target_link_options(kvsocket PRIVATE "-Wl,-z,max-page-size=16384")
