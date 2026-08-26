cmake_minimum_required(VERSION 3.21)
project(raven_native C)

if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "raven_native must be built as x64")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Locate JDK headers (jni.h / jvmti.h).
# ---------------------------------------------------------------------------
if(NOT DEFINED RAVEN_JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(RAVEN_JAVA_HOME "$ENV{JAVA_HOME}")
    endif()
endif()
if(NOT DEFINED RAVEN_JAVA_HOME OR RAVEN_JAVA_HOME STREQUAL "")
    message(FATAL_ERROR
        "Set -DRAVEN_JAVA_HOME=<jdk> (or export JAVA_HOME).")
endif()

if(WIN32)
    set(JNI_PLATFORM_DIR "${RAVEN_JAVA_HOME}/include/win32")
elseif(APPLE)
    set(JNI_PLATFORM_DIR "${RAVEN_JAVA_HOME}/include/darwin")
else()
    set(JNI_PLATFORM_DIR "${RAVEN_JAVA_HOME}/include/linux")
endif()

set(JNI_INCLUDE_DIRS
    "${RAVEN_JAVA_HOME}/include"
    "${JNI_PLATFORM_DIR}")

foreach(header jni.h jvmti.h)
    unset(_found CACHE)
    find_file(_found ${header} PATHS ${JNI_INCLUDE_DIRS} NO_DEFAULT_PATH)
    if(NOT _found)
        message(FATAL_ERROR "Missing ${header} under ${RAVEN_JAVA_HOME}/include")
    endif()
endforeach()

# ---------------------------------------------------------------------------
# Output directories.
# ---------------------------------------------------------------------------
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/dist")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/dist")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/dist")
if(MSVC)
    foreach(_cfg DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY_${_cfg} "${CMAKE_BINARY_DIR}/dist")
        set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${_cfg} "${CMAKE_BINARY_DIR}/dist")
        set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${_cfg} "${CMAKE_BINARY_DIR}/dist")
    endforeach()
endif()

# ---------------------------------------------------------------------------
# Platform sources.
# ---------------------------------------------------------------------------
set(BOOTSTRAP_COMMON_SOURCES src/bootstrap.c)

if(WIN32)
    # Windows: payloads embedded as RCDATA resources.
    if(NOT DEFINED RAVEN_FORGE_PAYLOAD_JAR OR RAVEN_FORGE_PAYLOAD_JAR STREQUAL "")
        message(FATAL_ERROR "Set -DRAVEN_FORGE_PAYLOAD_JAR=<path>.")
    endif()
    if(NOT DEFINED RAVEN_LUNAR_PAYLOAD_JAR OR RAVEN_LUNAR_PAYLOAD_JAR STREQUAL "")
        message(FATAL_ERROR "Set -DRAVEN_LUNAR_PAYLOAD_JAR=<path>.")
    endif()
    if(NOT EXISTS "${RAVEN_FORGE_PAYLOAD_JAR}")
        message(FATAL_ERROR "RAVEN_FORGE_PAYLOAD_JAR does not exist: ${RAVEN_FORGE_PAYLOAD_JAR}")
    endif()
    if(NOT EXISTS "${RAVEN_LUNAR_PAYLOAD_JAR}")
        message(FATAL_ERROR "RAVEN_LUNAR_PAYLOAD_JAR does not exist: ${RAVEN_LUNAR_PAYLOAD_JAR}")
    endif()

    get_filename_component(RAVEN_FORGE_PAYLOAD_JAR "${RAVEN_FORGE_PAYLOAD_JAR}" ABSOLUTE)
    file(TO_NATIVE_PATH "${RAVEN_FORGE_PAYLOAD_JAR}" _forge_payload_native)
    string(REPLACE "\\" "\\\\" RAVEN_FORGE_PAYLOAD_JAR_ESCAPED "${_forge_payload_native}")

    get_filename_component(RAVEN_LUNAR_PAYLOAD_JAR "${RAVEN_LUNAR_PAYLOAD_JAR}" ABSOLUTE)
    file(TO_NATIVE_PATH "${RAVEN_LUNAR_PAYLOAD_JAR}" _lunar_payload_native)
    string(REPLACE "\\" "\\\\" RAVEN_LUNAR_PAYLOAD_JAR_ESCAPED "${_lunar_payload_native}")

    file(TO_NATIVE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/src/app.manifest" _manifest_native)
    string(REPLACE "\\" "\\\\" RAVEN_MANIFEST_PATH "${_manifest_native}")

    configure_file(
        "${CMAKE_CURRENT_SOURCE_DIR}/src/payload.rc.in"
        "${CMAKE_CURRENT_BINARY_DIR}/payload.rc"
        @ONLY)

    enable_language(RC)

    set(PLATFORM_SOURCES src/platform_win32.c src/dllmain.c "${CMAKE_CURRENT_BINARY_DIR}/payload.rc")
    set(INJECTOR_SOURCES src/injector_win32.c)
else()
    # Linux/macOS: payloads embedded via objcopy or xxd.
    if(NOT DEFINED RAVEN_FORGE_PAYLOAD_JAR OR RAVEN_FORGE_PAYLOAD_JAR STREQUAL "")
        message(FATAL_ERROR "Set -DRAVEN_FORGE_PAYLOAD_JAR=<path>.")
    endif()
    if(NOT DEFINED RAVEN_LUNAR_PAYLOAD_JAR OR RAVEN_LUNAR_PAYLOAD_JAR STREQUAL "")
        message(FATAL_ERROR "Set -DRAVEN_LUNAR_PAYLOAD_JAR=<path>.")
    endif()

    # Generate C arrays from payload JARs using xxd.
    find_program(XXD xxd REQUIRED)
    set(PAYLOAD_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
    file(MAKE_DIRECTORY "${PAYLOAD_GEN_DIR}")

    add_custom_command(
        OUTPUT "${PAYLOAD_GEN_DIR}/payload_forge.c"
        COMMAND ${XXD} -i "${RAVEN_FORGE_PAYLOAD_JAR}" > "${PAYLOAD_GEN_DIR}/payload_forge.raw.c"
        COMMAND ${CMAKE_COMMAND}
            -DPAYLOAD_GEN_DIR=${PAYLOAD_GEN_DIR}
            -DPAYLOAD_NAME=forge
            -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/wrap_payload.cmake"
        DEPENDS "${RAVEN_FORGE_PAYLOAD_JAR}"
        COMMENT "Embedding Forge payload JAR"
    )
    add_custom_command(
        OUTPUT "${PAYLOAD_GEN_DIR}/payload_lunar.c"
        COMMAND ${XXD} -i "${RAVEN_LUNAR_PAYLOAD_JAR}" > "${PAYLOAD_GEN_DIR}/payload_lunar.raw.c"
        COMMAND ${CMAKE_COMMAND}
            -DPAYLOAD_GEN_DIR=${PAYLOAD_GEN_DIR}
            -DPAYLOAD_NAME=lunar
            -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/wrap_payload.cmake"
        DEPENDS "${RAVEN_LUNAR_PAYLOAD_JAR}"
        COMMENT "Embedding Lunar payload JAR"
    )

    set(PLATFORM_SOURCES
        src/platform_linux.c
        "${PAYLOAD_GEN_DIR}/payload_forge.c"
        "${PAYLOAD_GEN_DIR}/payload_lunar.c"
    )
    set(INJECTOR_SOURCES src/injector_linux.c)
endif()

# ---------------------------------------------------------------------------
# RavenNative shared library (.dll / .so)
# ---------------------------------------------------------------------------
add_library(RavenNative SHARED ${BOOTSTRAP_COMMON_SOURCES} ${PLATFORM_SOURCES})
set_target_properties(RavenNative PROPERTIES
    PREFIX ""
    OUTPUT_NAME "RavenNative"
    C_STANDARD 11)
target_include_directories(RavenNative PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
    ${JNI_INCLUDE_DIRS})

if(WIN32)
    target_link_libraries(RavenNative PRIVATE user32 advapi32)
    if(MSVC)
        target_compile_options(RavenNative PRIVATE /W3 /permissive- /D_CRT_SECURE_NO_WARNINGS /O2 /Oi /Gy)
    endif()
else()
    target_link_libraries(RavenNative PRIVATE dl pthread)
    target_compile_options(RavenNative PRIVATE -Wall -Wextra -O2 -fPIC)
    target_compile_definitions(RavenNative PRIVATE _GNU_SOURCE)
endif()

# ---------------------------------------------------------------------------
# RavenInjector executable
# ---------------------------------------------------------------------------
add_executable(RavenInjector ${INJECTOR_SOURCES})
set_target_properties(RavenInjector PROPERTIES
    OUTPUT_NAME "RavenInjector"
    C_STANDARD 11)

if(WIN32)
    target_link_libraries(RavenInjector PRIVATE user32 advapi32)
    if(MSVC)
        target_compile_options(RavenInjector PRIVATE /W3 /permissive- /D_CRT_SECURE_NO_WARNINGS /O2 /Oi /Gy)
    endif()
else()
    target_link_libraries(RavenInjector PRIVATE dl)
    target_compile_options(RavenInjector PRIVATE -Wall -Wextra -O2)
    target_compile_definitions(RavenInjector PRIVATE _GNU_SOURCE)
endif()

message(STATUS "raven_native platform   : ${CMAKE_SYSTEM_NAME}")
message(STATUS "raven_native Forge JAR  : ${RAVEN_FORGE_PAYLOAD_JAR}")
message(STATUS "raven_native Lunar JAR  : ${RAVEN_LUNAR_PAYLOAD_JAR}")
message(STATUS "raven_native output dir : ${CMAKE_BINARY_DIR}/dist")
