# Overlay triplet: same name the Windows build asks for, so nothing downstream changes.
#
# vcpkg picks its own toolchain from the host by default, which on Linux means a Linux
# compiler. Chainloading the cross toolchain makes the ports build with the same clang-cl
# and lld-link the loader itself uses, so their .lib files link against it.

set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE static)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Windows)

set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${CMAKE_CURRENT_LIST_DIR}/../windows-clang-cl.cmake")

# The chainloaded toolchain reads this, and vcpkg scrubs the environment without it.
set(VCPKG_ENV_PASSTHROUGH_UNTRACKED XWIN_ROOT)
