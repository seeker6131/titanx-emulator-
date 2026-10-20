cmake_minimum_required(VERSION 3.22.1)

project(titanx LANGUAGES CXX)

add_library(
    titanx
    SHARED
    titanx.cpp
)

target_compile_features(
    titanx
    PRIVATE
    cxx_std_17
)

target_compile_options(
    titanx
    PRIVATE
    -Wall
    -Wextra
    -O2
)
