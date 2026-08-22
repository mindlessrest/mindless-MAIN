cmake_minimum_required(VERSION 3.25)

# Tell CMake to use the native Windows RC compiler directly — not the clang wrapper.
# This must be set before project() which triggers compiler detection.
if (NOT DEFINED CMAKE_RC_COMPILER)
    find_program(MSVC_RC_COMPILER
        NAMES rc.exe
        PATHS
            "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64"
            "C:/Program Files (x86)/Windows Kits/10/bin/10.0.22621.0/x64"
            "C:/Program Files (x86)/Windows Kits/10/bin/10.0.19041.0/x64"
        NO_DEFAULT_PATH
    )
    if (MSVC_RC_COMPILER)
        set(CMAKE_RC_COMPILER "${MSVC_RC_COMPILER}" CACHE STRING "RC compiler" FORCE)
    endif()
endif()

project(MindlessLoader
    VERSION 0.2.0
    LANGUAGES CXX
)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

find_package(Freetype REQUIRED)
find_package(Stb REQUIRED)

set(SOURCES
    src/main.cpp
    src/application/application.cpp
    src/app/process_list.cpp
    src/app/screens.cpp
    src/renderer/d3d11_renderer.cpp
    src/renderer/font_atlas.cpp
    src/renderer/image.cpp
    src/ui/theme.cpp
    src/window/window.cpp
    src/input/input.cpp
    src/injection/injection_session.cpp
)

# Generate the .rc with the absolute icon path baked in.
set(ICON_PATH     "${CMAKE_SOURCE_DIR}/assets/logo.ico")
set(MANIFEST_PATH "${CMAKE_SOURCE_DIR}/src/app.manifest")
set(FONT_PATH     "${CMAKE_SOURCE_DIR}/assets/fonts/inter.ttf")
set(LOGO_PNG_PATH "${CMAKE_SOURCE_DIR}/assets/logo.png")
set(RAVEN_NATIVE_PATH   "${CMAKE_SOURCE_DIR}/assets/runtime/RavenNative.dll")
configure_file(src/resources.rc.in "${CMAKE_CURRENT_BINARY_DIR}/resources_gen.rc" @ONLY)

# Compile the .rc using rc.exe directly — bypasses cmake_llvm_rc which breaks
# when the CXX compiler is clang-cl.
find_program(RC_EXE
    NAMES rc.exe
    PATHS
        "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64"
        "C:/Program Files (x86)/Windows Kits/10/bin/10.0.22621.0/x64"
        "C:/Program Files (x86)/Windows Kits/10/bin/10.0.19041.0/x64"
        "C:/Program Files (x86)/Windows Kits/10/bin/10.0.20348.0/x64"
    NO_DEFAULT_PATH
)

set(RES_FILE "${CMAKE_CURRENT_BINARY_DIR}/resources_gen.res")
add_custom_command(
    OUTPUT  "${RES_FILE}"
    COMMAND "${RC_EXE}"
            /I "${CMAKE_SOURCE_DIR}/src"
            /fo "${RES_FILE}"
            "${CMAKE_CURRENT_BINARY_DIR}/resources_gen.rc"
    DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/resources_gen.rc"
            "${CMAKE_SOURCE_DIR}/src/resource.h"
            "${CMAKE_SOURCE_DIR}/src/app.manifest"
            "${CMAKE_SOURCE_DIR}/assets/logo.ico"
            "${CMAKE_SOURCE_DIR}/assets/logo.png"
            "${CMAKE_SOURCE_DIR}/assets/fonts/inter.ttf"
            "${CMAKE_SOURCE_DIR}/assets/runtime/RavenNative.dll"
    COMMENT "Compiling resources"
)
add_custom_target(MindlessResources DEPENDS "${RES_FILE}")

add_executable(MindlessLoader WIN32 ${SOURCES})
add_dependencies(MindlessLoader MindlessResources)

# Link the compiled .res directly — clang-cl linker accepts it as an object file.
target_link_options(MindlessLoader PRIVATE "${RES_FILE}")

target_include_directories(MindlessLoader
    PRIVATE
        src
        ${CMAKE_SOURCE_DIR}/external
        ${CMAKE_CURRENT_BINARY_DIR}   # so resource.h is found from the generated .rc
        ${Stb_INCLUDE_DIR}
)

target_link_libraries(MindlessLoader
    PRIVATE
        Freetype::Freetype
        d3d11.lib
        dxgi.lib
        d3dcompiler.lib
        dxguid.lib
        dwmapi.lib
        dcomp.lib
        shell32.lib
)

# RC compiler needs to find resource.h from src/
set(CMAKE_RC_FLAGS "${CMAKE_RC_FLAGS} /I \"${CMAKE_SOURCE_DIR}/src\"")

set_target_properties(MindlessLoader PROPERTIES
    LINK_FLAGS "/MANIFEST:NO"
)

if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(MindlessLoader PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -Wno-missing-field-initializers
        -Wno-c++98-compat
        -Wno-c++98-compat-pedantic
        -Wno-padded
        -Wno-unsafe-buffer-usage
        -Wno-switch-default
        -Wno-global-constructors
        -Wno-exit-time-destructors
        -Wno-language-extension-token
        -Wno-sign-conversion
        -Wno-double-promotion
        -Wno-implicit-int-float-conversion
        -Wno-old-style-cast
        -Wno-cast-function-type-strict
        -Wno-missing-prototypes
    )
endif()

target_compile_definitions(MindlessLoader PRIVATE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    UNICODE
    _UNICODE
    _CRT_SECURE_NO_WARNINGS
)

add_custom_command(TARGET MindlessLoader POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        "${CMAKE_SOURCE_DIR}/assets"
        "$<TARGET_FILE_DIR:MindlessLoader>/assets"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:MindlessLoader>"
        "${CMAKE_SOURCE_DIR}/MindlessLoader.exe"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:MindlessLoader>"
        "${CMAKE_SOURCE_DIR}/../MindlessLoader.exe"
    COMMENT "Copying assets and deploying exe"
)
