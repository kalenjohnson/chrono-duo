cmake_minimum_required(VERSION 3.22.1)
project(chronoduo C)

add_library(chronoduo SHARED gamestate.c)
target_link_libraries(chronoduo log z)
