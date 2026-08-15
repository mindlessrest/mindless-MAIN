cmake_minimum_required(VERSION 3.21)
project(raven_native C)

if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "raven_native must be built as x64")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Locate the JDK headers (jni.h / jvmti.h).
# Pass -DRAVEN_JAVA_HOME=... or set JAVA_HOME in the environment.
# ---------------------------------------------------------------------------
if(NOT DEFINED RAVEN_JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(RAVEN_JAVA_HOME "$ENV{JAVA_HOME}")
    endif()
endif()
if(NOT DEFINED RAVEN_JAVA_HOME OR RAVEN_JAVA_HOME STREQUAL "")
    message(FATAL_ERROR
        "Set -DRAVEN_JAVA_HOME=<jdk> (or export JAVA_HOME). "
        "The JDK must provide include/jni.h and include/win32/jvmti.h.")
endif()

set(JNI_INCLUDE_DIRS
    "${RAVEN_JAVA_HOME}/include"
    "${RAVEN_JAVA_HOME}/include/win32")
foreach(header jni.h jvmti.h)
    unset(_found CACHE)
    find_file(_found ${header} PATHS ${JNI_INCLUDE_DIRS} NO_DEFAULT_PATH)
    if(NOT _found)
        message(FATAL_ERROR "Missing ${header} under ${RAVEN_JAVA_HOME}/include")
    endif()
endforeach()

# ---------------------------------------------------------------------------
# Locate both runtime payloads. Forge uses SRG bytecode (RCDATA 421); Lunar's
# baked 1.8.9 classes use MCP/named bytecode (RCDATA 422).
# ---------------------------------------------------------------------------
if(NOT DEFINED RAVEN_FORGE_PAYLOAD_JAR OR RAVEN_FORGE_PAYLOAD_JAR STREQUAL "")
    message(FATAL_ERROR
        "Set -DRAVEN_FORGE_PAYLOAD_JAR=<path to the remapped Raven jar>.")
endif()
if(NOT DEFINED RAVEN_LUNAR_PAYLOAD_JAR OR RAVEN_LUNAR_PAYLOAD_JAR STREQUAL "")
    message(FATAL_ERROR
        "Set -DRAVEN_LUNAR_PAYLOAD_JAR=<path to the named Raven jar>.")
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

file(TO_NATIVE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/app.manifest" _manifest_native)
string(REPLACE "\\" "\\\\" RAVEN_MANIFEST_PATH "${_manifest_native}")

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/payload.rc.in"
    "${CMAKE_CURRENT_BINARY_DIR}/payload.rc"
    @ONLY)

enable_language(RC)

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
# RavenNative.dll — JNI/JVMTI bridge + embedded payload.
# ---------------------------------------------------------------------------
add_library(RavenNative SHARED
    dllmain.c
    "${CMAKE_CURRENT_BINARY_DIR}/payload.rc")
set_target_properties(RavenNative PROPERTIES
    PREFIX ""
    OUTPUT_NAME "RavenNative"
    C_STANDARD 11)
target_include_directories(RavenNative PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    ${JNI_INCLUDE_DIRS})
target_link_libraries(RavenNative PRIVATE user32 advapi32)
if(MSVC)
    target_compile_options(RavenNative PRIVATE /W3 /permissive- /D_CRT_SECURE_NO_WARNINGS)
endif()

# ---------------------------------------------------------------------------
# RavenInjector.exe — remote LoadLibraryW driver.
# ---------------------------------------------------------------------------
add_executable(RavenInjector injector.c)
set_target_properties(RavenInjector PROPERTIES
    OUTPUT_NAME "RavenInjector"
    C_STANDARD 11)
target_link_libraries(RavenInjector PRIVATE user32 advapi32)
if(MSVC)
    target_compile_options(RavenInjector PRIVATE /W3 /permissive- /D_CRT_SECURE_NO_WARNINGS)
endif()

message(STATUS "raven_native Forge payload: ${RAVEN_FORGE_PAYLOAD_JAR}")
message(STATUS "raven_native Lunar payload: ${RAVEN_LUNAR_PAYLOAD_JAR}")
message(STATUS "raven_native output dir : ${CMAKE_BINARY_DIR}/dist")
