cmake_minimum_required(VERSION 3.22.1)
project(pushreel_link_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(LINK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ableton-link")

add_library(pushreel_link_audio SHARED link_audio_jni.cpp pcm_fifo.hpp)

target_include_directories(pushreel_link_audio SYSTEM PRIVATE
    "${LINK_ROOT}/include"
    "${LINK_ROOT}/modules/asio-standalone/asio/include"
)

# Android uses the POSIX/Linux Link platform implementation. Asio is header-only.
target_compile_definitions(pushreel_link_audio PRIVATE
    ASIO_STANDALONE=1
    LINK_PLATFORM_LINUX=1
    LINK_PLATFORM_UNIX=1
)

target_compile_options(pushreel_link_audio PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

target_link_options(pushreel_link_audio PRIVATE
    "LINKER:-z,max-page-size=16384"
)

target_link_libraries(pushreel_link_audio PRIVATE android atomic log)
