cmake_minimum_required(VERSION 3.22.1)
project(monotonic_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(oboe REQUIRED CONFIG)
find_library(log-lib log)

add_library(
    monotonic_audio
    SHARED
    ClickSynth.cpp
    MetronomeEngine.cpp
    jni_bridge.cpp
)

target_link_options(
    monotonic_audio
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(
    monotonic_audio
    PRIVATE
    oboe::oboe
    ${log-lib}
)
