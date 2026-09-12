# Linux host, Windows x64 target, MSVC ABI.
#
# The Windows build already uses clang-cl and lld-link rather than cl.exe, and both run
# natively on Linux, so the compiler was never the obstacle. What is missing on a Linux box
# is the MSVC CRT and the Windows SDK; XWIN_ROOT points at an xwin splat directory holding
# both. Everything below just names them, because clang-cl cannot read the registry.
#
# CMake still reports MSVC with this toolchain, so every `if(MSVC)` branch in loader/ and
# client/native/ takes the same path it does on Windows and neither CMakeLists needs editing.

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR AMD64)

if(NOT XWIN_ROOT)
    if(DEFINED ENV{XWIN_ROOT})
        set(XWIN_ROOT "$ENV{XWIN_ROOT}")
    else()
        message(FATAL_ERROR "Set -DXWIN_ROOT=<xwin splat dir> or export XWIN_ROOT.")
    endif()
endif()
set(CMAKE_TRY_COMPILE_PLATFORM_VARIABLES XWIN_ROOT)

foreach(_dir crt/include crt/lib/x86_64 sdk/include/um sdk/lib/um/x86_64)
    if(NOT IS_DIRECTORY "${XWIN_ROOT}/${_dir}")
        message(FATAL_ERROR "XWIN_ROOT is missing ${_dir}. Re-run: xwin splat --output ${XWIN_ROOT}")
    endif()
endforeach()

find_program(MINDLESS_CLANG_CL NAMES clang-cl REQUIRED)
find_program(MINDLESS_LLD_LINK NAMES lld-link REQUIRED)
find_program(MINDLESS_LLVM_RC NAMES llvm-rc REQUIRED)
find_program(MINDLESS_LLVM_LIB NAMES llvm-lib REQUIRED)
find_program(MINDLESS_LLVM_MT NAMES llvm-mt)

set(CMAKE_C_COMPILER "${MINDLESS_CLANG_CL}")
set(CMAKE_CXX_COMPILER "${MINDLESS_CLANG_CL}")
set(CMAKE_LINKER "${MINDLESS_LLD_LINK}")
set(CMAKE_AR "${MINDLESS_LLVM_LIB}")
if(MINDLESS_LLVM_MT)
    set(CMAKE_MT "${MINDLESS_LLVM_MT}")
endif()

# llvm-rc accepts the same /I flags used by the Windows resource compiler.
set(CMAKE_RC_COMPILER "${MINDLESS_LLVM_RC}")

set(CMAKE_C_COMPILER_TARGET x86_64-pc-windows-msvc)
set(CMAKE_CXX_COMPILER_TARGET x86_64-pc-windows-msvc)
set(CMAKE_MSVC_RUNTIME_LIBRARY MultiThreaded)

set(_mindless_includes
    "/imsvc${XWIN_ROOT}/crt/include"
    "/imsvc${XWIN_ROOT}/sdk/include/ucrt"
    "/imsvc${XWIN_ROOT}/sdk/include/um"
    "/imsvc${XWIN_ROOT}/sdk/include/shared"
    "/imsvc${XWIN_ROOT}/sdk/include/winrt")
list(JOIN _mindless_includes " " _mindless_include_flags)

set(_mindless_flags "--target=x86_64-pc-windows-msvc ${_mindless_include_flags} -Wno-unused-command-line-argument")
set(CMAKE_C_FLAGS_INIT "${_mindless_flags}")
set(CMAKE_CXX_FLAGS_INIT "${_mindless_flags}")
set(CMAKE_RC_FLAGS_INIT "-I\"${XWIN_ROOT}/sdk/include/um\" -I\"${XWIN_ROOT}/sdk/include/shared\"")

set(_mindless_libpaths
    "/libpath:\"${XWIN_ROOT}/crt/lib/x86_64\""
    "/libpath:\"${XWIN_ROOT}/sdk/lib/ucrt/x86_64\""
    "/libpath:\"${XWIN_ROOT}/sdk/lib/um/x86_64\"")
list(JOIN _mindless_libpaths " " _mindless_link_flags)
set(CMAKE_EXE_LINKER_FLAGS_INIT "${_mindless_link_flags}")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "${_mindless_link_flags}")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "${_mindless_link_flags}")

# Host tools stay on the host; libraries and headers come from the sysroot and from vcpkg.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
