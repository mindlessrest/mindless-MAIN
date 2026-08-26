cmake_minimum_required(VERSION 3.21)
project(MindlessLoaderTUI CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(ftxui
    GIT_REPOSITORY https://github.com/ArthurSonzogni/FTXUI
    GIT_TAG        v5.0.0
)
FetchContent_MakeAvailable(ftxui)

# Embed the bootstrapper payload
set(RAVEN_NATIVE_PATH "" CACHE FILEPATH "Path to RavenNative.dll/.so to embed")
if(NOT RAVEN_NATIVE_PATH)
    if(WIN32)
        set(RAVEN_NATIVE_PATH "${CMAKE_SOURCE_DIR}/../../bootstrapper/build/dist/RavenNative.dll")
    else()
        set(RAVEN_NATIVE_PATH "${CMAKE_SOURCE_DIR}/../../bootstrapper/build/dist/RavenNative.so")
    endif()
endif()

set(GENERATED_DIR "${CMAKE_BINARY_DIR}/generated")
set(EMBEDDED_PAYLOAD_C "${GENERATED_DIR}/embedded_payload.c")

add_custom_command(
    OUTPUT "${EMBEDDED_PAYLOAD_C}"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${GENERATED_DIR}"
    COMMAND ${CMAKE_COMMAND}
        -DINPUT_FILE=${RAVEN_NATIVE_PATH}
        -DOUTPUT_FILE=${EMBEDDED_PAYLOAD_C}
        -P "${CMAKE_SOURCE_DIR}/cmake/embed_payload.cmake"
    DEPENDS "${RAVEN_NATIVE_PATH}"
    COMMENT "Embedding RavenNative payload into loader"
)

set(SOURCES
    src/main.cpp
    src/ui.cpp
    src/process_list.cpp
    src/injection.cpp
    src/progress.cpp
    "${EMBEDDED_PAYLOAD_C}"
)

add_executable(MindlessLoaderTUI ${SOURCES})

target_include_directories(MindlessLoaderTUI PRIVATE src "${GENERATED_DIR}")

target_link_libraries(MindlessLoaderTUI PRIVATE
    ftxui::screen
    ftxui::dom
    ftxui::component
)

if(WIN32)
    target_link_libraries(MindlessLoaderTUI PRIVATE advapi32)
    target_compile_definitions(MindlessLoaderTUI PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
else()
    target_link_libraries(MindlessLoaderTUI PRIVATE pthread dl)
    target_compile_definitions(MindlessLoaderTUI PRIVATE _GNU_SOURCE)
endif()

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(MindlessLoaderTUI PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()
