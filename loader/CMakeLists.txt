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
    LANGUAGES C CXX
)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if (NOT CMAKE_MSVC_RUNTIME_LIBRARY)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

option(MINDLESS_FETCH_DEPS "Build third-party dependencies from source" OFF)
if (MINDLESS_FETCH_DEPS)
    include(FetchContent)
    set(FETCHCONTENT_UPDATES_DISCONNECTED ON)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
    set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
    set(BUILD_CURL_EXE OFF CACHE BOOL "" FORCE)
    set(BUILD_LIBCURL_DOCS OFF CACHE BOOL "" FORCE)
    set(BUILD_MISC_DOCS OFF CACHE BOOL "" FORCE)
    set(CURL_ENABLE_SSL ON CACHE BOOL "" FORCE)
    set(CURL_USE_SCHANNEL ON CACHE BOOL "" FORCE)
    set(CURL_USE_OPENSSL OFF CACHE BOOL "" FORCE)
    set(CURL_USE_LIBPSL OFF CACHE BOOL "" FORCE)
    set(CURL_USE_LIBSSH2 OFF CACHE BOOL "" FORCE)
    set(CURL_USE_GSSAPI OFF CACHE BOOL "" FORCE)
    set(CURL_ZLIB OFF CACHE BOOL "" FORCE)
    set(CURL_BROTLI OFF CACHE BOOL "" FORCE)
    set(CURL_ZSTD OFF CACHE BOOL "" FORCE)
    set(HTTP_ONLY ON CACHE BOOL "" FORCE)
    foreach(_protocol DICT FILE FTP GOPHER IMAP LDAP LDAPS MQTT POP3 RTSP SMB SMTP TELNET TFTP)
        set(CURL_DISABLE_${_protocol} ON CACHE BOOL "" FORCE)
    endforeach()

    FetchContent_Declare(freetype
        GIT_REPOSITORY https://github.com/freetype/freetype.git
        GIT_TAG VER-2-14-3
        GIT_SHALLOW TRUE
        SYSTEM)
    FetchContent_Declare(curl
        URL https://github.com/curl/curl/releases/download/curl-8_22_0/curl-8.22.0.tar.xz
        URL_HASH SHA256=f7ef3ae8a22e521f289803fe93543eb64c329b58aa73a9e224dfd915a2a5f4f7
        SYSTEM)
    FetchContent_Declare(json
        URL https://github.com/nlohmann/json/releases/download/v3.12.0/json.tar.xz
        URL_HASH SHA256=42f6e95cad6ec532fd372391373363b62a14af6d771056dbfc86160e6dfff7aa
        SYSTEM)
    FetchContent_Declare(stb
        GIT_REPOSITORY https://github.com/nothings/stb.git
        GIT_TAG 2c980bb59875b0d32144a71867fbdebb2f77cd20
        SYSTEM)
    FetchContent_MakeAvailable(freetype curl json stb)
    if (NOT TARGET Freetype::Freetype)
        add_library(Freetype::Freetype ALIAS freetype)
    endif()
    set(Stb_INCLUDE_DIR "${stb_SOURCE_DIR}")
else()
    find_package(Freetype REQUIRED)
    find_package(Stb REQUIRED)
    find_package(CURL REQUIRED)
    find_package(nlohmann_json CONFIG REQUIRED)
endif()

# Auth SDK — build as a static library from the SDK source bundled in the repo.
# Mirrors the Java SDK handling in client/build.gradle.kts: prefer the bundled
# copy so a clean checkout builds, fall back to a local SDK checkout for
# machines that still develop against one.
get_filename_component(MINDLESS_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
set(BUNDLED_AUTHSDK_DIR "${MINDLESS_ROOT_DIR}/shared/authsdk/cpp")
set(LOCAL_AUTHSDK_DIR   "$ENV{USERPROFILE}/authsdk/cpp")
if (EXISTS "${BUNDLED_AUTHSDK_DIR}/src/authclient.cpp")
    set(AUTHSDK_DIR "${BUNDLED_AUTHSDK_DIR}")
elseif (EXISTS "${LOCAL_AUTHSDK_DIR}/src/authclient.cpp")
    set(AUTHSDK_DIR "${LOCAL_AUTHSDK_DIR}")
else()
    message(FATAL_ERROR
        "Auth SDK C++ sources not found. Expected them at "
        "${BUNDLED_AUTHSDK_DIR} or ${LOCAL_AUTHSDK_DIR}.")
endif()
message(STATUS "Auth SDK source: ${AUTHSDK_DIR}")
add_library(authclient STATIC
    "${AUTHSDK_DIR}/src/crypto.cpp"
    "${AUTHSDK_DIR}/src/hwid.cpp"
    "${AUTHSDK_DIR}/src/ipc.cpp"
    "${AUTHSDK_DIR}/src/heartbeat.cpp"
    "${AUTHSDK_DIR}/src/authclient.cpp"
)
target_include_directories(authclient PUBLIC "${AUTHSDK_DIR}/include")
target_link_libraries(authclient PUBLIC
    CURL::libcurl
    nlohmann_json::nlohmann_json
    bcrypt
    wbemuuid ole32 oleaut32
)
set_target_properties(authclient PROPERTIES
    CXX_STANDARD 17
    MSVC_RUNTIME_LIBRARY "${CMAKE_MSVC_RUNTIME_LIBRARY}"
)

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
    src/injection/manual_map.cpp
    src/protection/protection.cpp
)

# Generate the .rc with the absolute icon path baked in.
set(ICON_PATH     "${CMAKE_SOURCE_DIR}/assets/logo.ico")
set(MANIFEST_PATH "${CMAKE_SOURCE_DIR}/src/app.manifest")
set(FONT_PATH     "${CMAKE_SOURCE_DIR}/assets/fonts/inter.ttf")
set(LOGO_PNG_PATH "${CMAKE_SOURCE_DIR}/assets/logo.png")
set(LUNAR_PNG_PATH "${CMAKE_SOURCE_DIR}/assets/lunar.png")
configure_file(src/resources.rc.in "${CMAKE_CURRENT_BINARY_DIR}/resources_gen.rc" @ONLY)

# Compile the .rc using rc.exe directly — bypasses cmake_llvm_rc which breaks
# when the CXX compiler is clang-cl.
if (CMAKE_RC_COMPILER)
    set(RC_EXE "${CMAKE_RC_COMPILER}")
else()
    find_program(RC_EXE
        NAMES rc.exe
        PATHS
            "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64"
            "C:/Program Files (x86)/Windows Kits/10/bin/10.0.22621.0/x64"
            "C:/Program Files (x86)/Windows Kits/10/bin/10.0.19041.0/x64"
            "C:/Program Files (x86)/Windows Kits/10/bin/10.0.20348.0/x64"
        NO_DEFAULT_PATH
        REQUIRED
    )
endif()

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
            "${CMAKE_SOURCE_DIR}/assets/lunar.png"
            "${CMAKE_SOURCE_DIR}/assets/fonts/inter.ttf"
    COMMENT "Compiling resources"
)
add_custom_target(MindlessResources DEPENDS "${RES_FILE}")

add_executable(MindlessLoader WIN32 ${SOURCES})
add_dependencies(MindlessLoader MindlessResources)

add_executable(MindlessDev
    src/dev_main.cpp
    src/injection/injection_session.cpp
    src/injection/manual_map.cpp
)
target_include_directories(MindlessDev PRIVATE src ${CMAKE_SOURCE_DIR}/..)
target_compile_definitions(MindlessDev PRIVATE
    _NONPROD=1
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    UNICODE
    _UNICODE
    _CRT_SECURE_NO_WARNINGS
)
target_link_libraries(MindlessDev PRIVATE advapi32.lib)
set_target_properties(MindlessDev PROPERTIES
    OUTPUT_NAME "dev"
    LINK_FLAGS "/INCREMENTAL:NO"
)
add_custom_command(TARGET MindlessDev POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:MindlessDev>"
        "${CMAKE_SOURCE_DIR}/../dev.exe"
    COMMENT "Deploying developer loader"
)

option(MINDLESS_PRODUCTION "Build without development-only behavior" OFF)
option(MINDLESS_PRIVATE_PDB "Emit a private PDB for post-build protection" OFF)
if (NOT MINDLESS_PRODUCTION)
    target_compile_definitions(MindlessLoader PRIVATE _NONPROD=1)
endif()

if (MINDLESS_PRIVATE_PDB)
    target_compile_options(MindlessLoader PRIVATE "$<$<CONFIG:Release>:/Zi>")
    target_link_options(MindlessLoader PRIVATE
        "$<$<CONFIG:Release>:/DEBUG:FULL>"
        "$<$<CONFIG:Release>:/PDB:$<TARGET_FILE_DIR:MindlessLoader>/MindlessLoader.pdb>"
    )
endif()

# Link the compiled .res directly — clang-cl linker accepts it as an object file.
target_link_options(MindlessLoader PRIVATE "${RES_FILE}")

target_include_directories(MindlessLoader
    PRIVATE
        src
        ${CMAKE_SOURCE_DIR}/..
        ${CMAKE_SOURCE_DIR}/external
        ${CMAKE_CURRENT_BINARY_DIR}   # so resource.h is found from the generated .rc
)
target_include_directories(MindlessLoader SYSTEM PRIVATE ${Stb_INCLUDE_DIR})

target_link_libraries(MindlessLoader
    PRIVATE
        Freetype::Freetype
        authclient
        d3d11.lib
        dxgi.lib
        d3dcompiler.lib
        dxguid.lib
        dwmapi.lib
        dcomp.lib
        shell32.lib
        crypt32.lib
)

# RC compiler needs to find resource.h from src/
set(CMAKE_RC_FLAGS "${CMAKE_RC_FLAGS} /I \"${CMAKE_SOURCE_DIR}/src\"")

target_precompile_headers(MindlessLoader PRIVATE
    <windows.h>
    <d3d11.h>
    <dxgi.h>
    <dwmapi.h>
    <string>
    <vector>
    <memory>
    <functional>
    <cmath>
    <algorithm>
    <cstdint>
    <chrono>
    <thread>
)

set_target_properties(MindlessLoader PROPERTIES
    LINK_FLAGS "/MANIFEST:NO /INCREMENTAL:NO /OPT:REF /OPT:ICF /RELEASE"
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
        -Wno-covered-switch-default
        -Wno-c2y-extensions
        -Wno-global-constructors
        -Wno-exit-time-destructors
        -Wno-language-extension-token
        -Wno-sign-conversion
        -Wno-double-promotion
        -Wno-implicit-int-float-conversion
        -Wno-old-style-cast
        -Wno-cast-function-type-strict
        -Wno-missing-prototypes
        -Wno-nonportable-system-include-path
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
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:MindlessLoader>"
        "${CMAKE_SOURCE_DIR}/../MindlessLoader.exe"
    COMMENT "Deploying loader"
)
if (NOT MINDLESS_PRODUCTION)
    add_custom_command(TARGET MindlessLoader POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CMAKE_SOURCE_DIR}/assets"
            "$<TARGET_FILE_DIR:MindlessLoader>/assets"
        COMMENT "Copying development assets"
    )
endif()
