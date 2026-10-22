cmake_minimum_required(VERSION 3.22.1)
project(distress_detector LANGUAGES C CXX)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
add_subdirectory("${PROJECT_ROOT}/third_party/whisper.cpp" whisper.cpp)

add_library(distress_whisper SHARED whisper_jni.cpp)
target_compile_features(distress_whisper PRIVATE cxx_std_17)
target_include_directories(distress_whisper PRIVATE
    "${PROJECT_ROOT}/third_party/whisper.cpp/include"
)
target_link_libraries(distress_whisper PRIVATE whisper android log)
