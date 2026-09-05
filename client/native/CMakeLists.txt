cmake_minimum_required(VERSION 3.21)
project(mindless_native C)

if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "mindless_native must be built as x64")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Locate the JDK headers (jni.h / jvmti.h).
# Pass -DMINDLESS_JAVA_HOME=... or set JAVA_HOME in the environment.
# ---------------------------------------------------------------------------
if(NOT DEFINED MINDLESS_JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(MINDLESS_JAVA_HOME "$ENV{JAVA_HOME}")
    endif()
endif()
if(NOT DEFINED MINDLESS_JAVA_HOME OR MINDLESS_JAVA_HOME STREQUAL "")
    message(FATAL_ERROR
        "Set -DMINDLESS_JAVA_HOME=<jdk> (or export JAVA_HOME). "
        "The JDK must provide include/jni.h and include/win32/jvmti.h.")
endif()

set(JNI_INCLUDE_DIRS
    "${MINDLESS_JAVA_HOME}/include"
    "${MINDLESS_JAVA_HOME}/include/win32")
foreach(header jni.h jvmti.h)
    unset(_found CACHE)
    find_file(_found ${header} PATHS ${JNI_INCLUDE_DIRS} NO_DEFAULT_PATH)
    if(NOT _found)
        message(FATAL_ERROR "Missing ${header} under ${MINDLESS_JAVA_HOME}/include")
    endif()
endforeach()

# ---------------------------------------------------------------------------
# Locate both runtime payloads. Forge uses SRG bytecode (RCDATA 421); Lunar's
# baked 1.8.9 classes use MCP/named bytecode (RCDATA 422).
# ---------------------------------------------------------------------------
if(NOT DEFINED MINDLESS_FORGE_PAYLOAD_JAR OR MINDLESS_FORGE_PAYLOAD_JAR STREQUAL "")
    message(FATAL_ERROR
        "Set -DMINDLESS_FORGE_PAYLOAD_JAR=<path to the remapped Mindless jar>.")
endif()
if(NOT DEFINED MINDLESS_LUNAR_PAYLOAD_JAR OR MINDLESS_LUNAR_PAYLOAD_JAR STREQUAL "")
    message(FATAL_ERROR
        "Set -DMINDLESS_LUNAR_PAYLOAD_JAR=<path to the named Mindless jar>.")
endif()
if(NOT EXISTS "${MINDLESS_FORGE_PAYLOAD_JAR}")
    message(FATAL_ERROR "MINDLESS_FORGE_PAYLOAD_JAR does not exist: ${MINDLESS_FORGE_PAYLOAD_JAR}")
endif()
if(NOT EXISTS "${MINDLESS_LUNAR_PAYLOAD_JAR}")
    message(FATAL_ERROR "MINDLESS_LUNAR_PAYLOAD_JAR does not exist: ${MINDLESS_LUNAR_PAYLOAD_JAR}")
endif()

get_filename_component(MINDLESS_FORGE_PAYLOAD_JAR "${MINDLESS_FORGE_PAYLOAD_JAR}" ABSOLUTE)
file(TO_NATIVE_PATH "${MINDLESS_FORGE_PAYLOAD_JAR}" _forge_payload_native)
string(REPLACE "\\" "\\\\" MINDLESS_FORGE_PAYLOAD_JAR_ESCAPED "${_forge_payload_native}")

get_filename_component(MINDLESS_LUNAR_PAYLOAD_JAR "${MINDLESS_LUNAR_PAYLOAD_JAR}" ABSOLUTE)
file(TO_NATIVE_PATH "${MINDLESS_LUNAR_PAYLOAD_JAR}" _lunar_payload_native)
string(REPLACE "\\" "\\\\" MINDLESS_LUNAR_PAYLOAD_JAR_ESCAPED "${_lunar_payload_native}")
file(SHA256 "${MINDLESS_FORGE_PAYLOAD_JAR}" MINDLESS_FORGE_PAYLOAD_SHA256)
file(SHA256 "${MINDLESS_LUNAR_PAYLOAD_JAR}" MINDLESS_LUNAR_PAYLOAD_SHA256)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${MINDLESS_FORGE_PAYLOAD_JAR}"
    "${MINDLESS_LUNAR_PAYLOAD_JAR}")
configure_file(payload_hashes.h.in "${CMAKE_CURRENT_BINARY_DIR}/payload_hashes.h" @ONLY)

file(TO_NATIVE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/app.manifest" _manifest_native)
string(REPLACE "\\" "\\\\" MINDLESS_MANIFEST_PATH "${_manifest_native}")

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
# MindlessNative.dll — JNI/JVMTI bridge + embedded payload.
# ---------------------------------------------------------------------------
add_library(MindlessNative SHARED
    dllmain.c
    "${CMAKE_CURRENT_BINARY_DIR}/payload.rc")
if(CMAKE_C_COMPILER_ID MATCHES "Clang")
    # Voyager intentionally ships without a linker. This custom CMake linker
    # type makes the compiler driver resolve the normal LLVM lld-link from
    # PATH instead of looking beside Voyager for a nonexistent bundled lld.
    set(CMAKE_C_USING_LINKER_MindlessLLD "-fuse-ld=lld-link")
    set_property(TARGET MindlessNative PROPERTY LINKER_TYPE MindlessLLD)
endif()
set_target_properties(MindlessNative PROPERTIES
    PREFIX ""
    OUTPUT_NAME "MindlessNative"
    C_STANDARD 11)
target_include_directories(MindlessNative PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/../.."
    "${CMAKE_CURRENT_BINARY_DIR}"
    ${JNI_INCLUDE_DIRS})
target_link_libraries(MindlessNative PRIVATE user32 advapi32)
if(MSVC)
    target_compile_options(MindlessNative PRIVATE /W3 /permissive- /D_CRT_SECURE_NO_WARNINGS /O2 /Oi /Gy)
endif()

# ---------------------------------------------------------------------------
# MindlessInjector.exe — remote LoadLibraryW driver.
# ---------------------------------------------------------------------------
add_executable(MindlessInjector injector.c)
set_target_properties(MindlessInjector PROPERTIES
    OUTPUT_NAME "MindlessInjector"
    C_STANDARD 11)
target_link_libraries(MindlessInjector PRIVATE user32 advapi32)
if(MSVC)
    target_compile_options(MindlessInjector PRIVATE /W3 /permissive- /D_CRT_SECURE_NO_WARNINGS /O2 /Oi /Gy)
endif()

message(STATUS "mindless_native Forge payload: ${MINDLESS_FORGE_PAYLOAD_JAR}")
message(STATUS "mindless_native Lunar payload: ${MINDLESS_LUNAR_PAYLOAD_JAR}")
message(STATUS "mindless_native output dir : ${CMAKE_BINARY_DIR}/dist")
