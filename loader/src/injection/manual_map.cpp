#include "manual_map.hpp"
#include "auth/xorstr.hpp"
#include <TlHelp32.h>
#include <algorithm>
#include <cstring>
#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>
namespace mindless
{
    namespace
    {
        void mm_log(const char* format, ...)
        {
            char message[512];
            char line[640];
            va_list arguments;
            va_start(arguments, format);
            _vsnprintf_s(message, sizeof(message), _TRUNCATE, format, arguments);
            va_end(arguments);
            _snprintf_s(line, sizeof(line), _TRUNCATE, "[Mindless] MM: %s\n", message);
            OutputDebugStringA(line);
        }

        using NtAllocateVirtualMemory_t = NTSTATUS(NTAPI*)(
            HANDLE ProcessHandle, PVOID* BaseAddress, ULONG_PTR ZeroBits,
            PSIZE_T RegionSize, ULONG AllocationType, ULONG Protect);
        using NtWriteVirtualMemory_t = NTSTATUS(NTAPI*)(
            HANDLE ProcessHandle, PVOID BaseAddress, PVOID Buffer,
            SIZE_T NumberOfBytesToWrite, PSIZE_T NumberOfBytesWritten);
        using NtFreeVirtualMemory_t = NTSTATUS(NTAPI*)(
            HANDLE ProcessHandle, PVOID* BaseAddress, PSIZE_T RegionSize,
            ULONG FreeType);
        using NtCreateThreadEx_t = NTSTATUS(NTAPI*)(
            PHANDLE ThreadHandle, ACCESS_MASK DesiredAccess, PVOID ObjectAttributes,
            HANDLE ProcessHandle, PVOID StartRoutine, PVOID Argument,
            ULONG CreateFlags, SIZE_T ZeroBits, SIZE_T StackSize,
            SIZE_T MaximumStackSize, PVOID AttributeList);
        using NtWaitForSingleObject_t = NTSTATUS(NTAPI*)(
            HANDLE Handle, BOOLEAN Alertable, PLARGE_INTEGER Timeout);
        struct NtApi
        {
            NtAllocateVirtualMemory_t AllocateVirtualMemory;
            NtWriteVirtualMemory_t WriteVirtualMemory;
            NtFreeVirtualMemory_t FreeVirtualMemory;
            NtCreateThreadEx_t CreateThreadEx;
            NtWaitForSingleObject_t WaitForSingleObject;
        };
        bool resolve_nt_api(NtApi& api)
        {
            HMODULE ntdll = GetModuleHandleW(XORSTRW(L"ntdll.dll"));
            if (!ntdll) return false;
            api.AllocateVirtualMemory = (NtAllocateVirtualMemory_t)
                GetProcAddress(ntdll, XORSTR("NtAllocateVirtualMemory"));
            api.WriteVirtualMemory = (NtWriteVirtualMemory_t)
                GetProcAddress(ntdll, XORSTR("NtWriteVirtualMemory"));
            api.FreeVirtualMemory = (NtFreeVirtualMemory_t)
                GetProcAddress(ntdll, XORSTR("NtFreeVirtualMemory"));
            api.CreateThreadEx = (NtCreateThreadEx_t)
                GetProcAddress(ntdll, XORSTR("NtCreateThreadEx"));
            api.WaitForSingleObject = (NtWaitForSingleObject_t)
                GetProcAddress(ntdll, XORSTR("NtWaitForSingleObject"));
            return api.AllocateVirtualMemory && api.WriteVirtualMemory
                && api.FreeVirtualMemory && api.CreateThreadEx && api.WaitForSingleObject;
        }

        struct RemoteModule
        {
            uintptr_t base = 0;
            DWORD size = 0;
        };

        RemoteModule find_remote_module(uint32_t processId, const wchar_t* moduleName)
        {
            HANDLE snap = CreateToolhelp32Snapshot(
                TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, processId);
            if (snap == INVALID_HANDLE_VALUE) return {};

            MODULEENTRY32W me = {};
            me.dwSize = sizeof(me);
            RemoteModule result = {};
            if (Module32FirstW(snap, &me))
            {
                do
                {
                    if (_wcsicmp(me.szModule, moduleName) == 0)
                    {
                        result.base = reinterpret_cast<uintptr_t>(me.modBaseAddr);
                        result.size = me.modBaseSize;
                        break;
                    }
                } while (Module32NextW(snap, &me));
            }
            CloseHandle(snap);
            return result;
        }

        bool read_remote(HANDLE process, uintptr_t address, void* output, size_t size)
        {
            SIZE_T bytesRead = 0;
            return size != 0
                && ReadProcessMemory(process, reinterpret_cast<const void*>(address),
                    output, size, &bytesRead)
                && bytesRead == size;
        }

        bool read_remote_string(HANDLE process, uintptr_t address,
            std::string& output, size_t maxLength = 512)
        {
            output.clear();
            for (size_t i = 0; i < maxLength; ++i)
            {
                char value = 0;
                if (!read_remote(process, address + i, &value, sizeof(value)))
                    return false;
                if (value == '\0') return true;
                output.push_back(value);
            }
            return false;
        }

        uintptr_t remote_export(HANDLE process, uint32_t processId,
            const wchar_t* moduleName, const char* procName, WORD ordinal,
            bool byOrdinal, unsigned depth = 0)
        {
            if (!moduleName || depth > 8) return 0;

            RemoteModule module = find_remote_module(processId, moduleName);
            if (!module.base || module.size < sizeof(IMAGE_DOS_HEADER)) return 0;

            IMAGE_DOS_HEADER dos = {};
            if (!read_remote(process, module.base, &dos, sizeof(dos))
                || dos.e_magic != IMAGE_DOS_SIGNATURE || dos.e_lfanew <= 0
                || static_cast<uintptr_t>(dos.e_lfanew) + sizeof(IMAGE_NT_HEADERS64) > module.size)
                return 0;

            IMAGE_NT_HEADERS64 pe = {};
            if (!read_remote(process, module.base + dos.e_lfanew, &pe, sizeof(pe))
                || pe.Signature != IMAGE_NT_SIGNATURE
                || pe.OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC)
                return 0;

            const IMAGE_DATA_DIRECTORY& exportData =
                pe.OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT];
            if (!exportData.VirtualAddress
                || exportData.VirtualAddress + sizeof(IMAGE_EXPORT_DIRECTORY) > module.size)
                return 0;

            IMAGE_EXPORT_DIRECTORY exports = {};
            if (!read_remote(process, module.base + exportData.VirtualAddress,
                    &exports, sizeof(exports))
                || exports.NumberOfFunctions == 0 || exports.NumberOfFunctions > (1u << 20))
                return 0;

            DWORD functionIndex = 0;
            if (byOrdinal)
            {
                if (ordinal < exports.Base) return 0;
                functionIndex = ordinal - exports.Base;
                if (functionIndex >= exports.NumberOfFunctions) return 0;
            }
            else
            {
                if (!procName || exports.NumberOfNames == 0
                    || exports.NumberOfNames > (1u << 20))
                    return 0;

                const size_t namesSize = static_cast<size_t>(exports.NumberOfNames) * sizeof(DWORD);
                const size_t ordinalsSize = static_cast<size_t>(exports.NumberOfNames) * sizeof(WORD);
                if (exports.AddressOfNames >= module.size
                    || namesSize > module.size - exports.AddressOfNames
                    || exports.AddressOfNameOrdinals >= module.size
                    || ordinalsSize > module.size - exports.AddressOfNameOrdinals)
                    return 0;

                std::vector<DWORD> names(exports.NumberOfNames);
                std::vector<WORD> ordinals(exports.NumberOfNames);
                if (!read_remote(process, module.base + exports.AddressOfNames,
                        names.data(), namesSize)
                    || !read_remote(process, module.base + exports.AddressOfNameOrdinals,
                        ordinals.data(), ordinalsSize))
                    return 0;

                bool found = false;
                for (DWORD i = 0; i < exports.NumberOfNames; ++i)
                {
                    if (names[i] >= module.size) continue;
                    std::string candidate;
                    if (read_remote_string(process, module.base + names[i], candidate)
                        && candidate == procName)
                    {
                        functionIndex = ordinals[i];
                        found = functionIndex < exports.NumberOfFunctions;
                        break;
                    }
                }
                if (!found) return 0;
            }

            const size_t functionsSize =
                static_cast<size_t>(exports.NumberOfFunctions) * sizeof(DWORD);
            if (exports.AddressOfFunctions >= module.size
                || functionsSize > module.size - exports.AddressOfFunctions)
                return 0;
            DWORD functionRva = 0;
            if (!read_remote(process,
                    module.base + exports.AddressOfFunctions
                        + static_cast<uintptr_t>(functionIndex) * sizeof(DWORD),
                    &functionRva, sizeof(functionRva))
                || functionRva == 0)
                return 0;

            const uint64_t exportEnd = static_cast<uint64_t>(exportData.VirtualAddress)
                + exportData.Size;
            if (functionRva >= exportData.VirtualAddress && functionRva < exportEnd)
            {
                std::string forwarder;
                if (!read_remote_string(process, module.base + functionRva, forwarder))
                    return 0;
                const size_t separator = forwarder.rfind('.');
                if (separator == std::string::npos || separator == 0
                    || separator + 1 >= forwarder.size())
                    return 0;

                std::string forwardedModule = forwarder.substr(0, separator);
                if (forwardedModule.find('.') == std::string::npos)
                    forwardedModule += ".dll";
                wchar_t wideModule[256] = {};
                if (!MultiByteToWideChar(CP_ACP, 0, forwardedModule.c_str(), -1,
                        wideModule, static_cast<int>(_countof(wideModule))))
                    return 0;

                const std::string forwardedProc = forwarder.substr(separator + 1);
                if (forwardedProc.size() > 1 && forwardedProc[0] == '#')
                {
                    char* end = nullptr;
                    const unsigned long forwardedOrdinal =
                        strtoul(forwardedProc.c_str() + 1, &end, 10);
                    if (!end || *end != '\0' || forwardedOrdinal > 0xFFFF) return 0;
                    return remote_export(process, processId, wideModule, nullptr,
                        static_cast<WORD>(forwardedOrdinal), true, depth + 1);
                }
                return remote_export(process, processId, wideModule,
                    forwardedProc.c_str(), 0, false, depth + 1);
            }

            if (functionRva >= module.size) return 0;
            return module.base + functionRva;
        }

        uintptr_t remote_proc(HANDLE process, uint32_t processId,
            const wchar_t* moduleName, const char* procName)
        {
            return remote_export(process, processId, moduleName, procName, 0, false);
        }
        // ---------------------------------------------------------------------------
        // Trampoline context — written to remote process alongside the trampoline code.
        // The trampoline reads this struct to call RtlAddFunctionTable + DllMain.
        // ---------------------------------------------------------------------------
        struct TrampolineCtx
        {
            void* fnRtlAddFunctionTable;  // +0x00: address of RtlAddFunctionTable (or nullptr to skip)
            void* pdataAddr;              // +0x08: pointer to RUNTIME_FUNCTION array in remote image
            uint32_t pdataCount;          // +0x10: number of entries
            uint32_t _pad;                // +0x14: padding for alignment
            void* imageBase;              // +0x18: remote image base (hInstance for DllMain)
            void* entryPoint;             // +0x20: absolute address of DllMain
        };
        // ---------------------------------------------------------------------------
        // Handcrafted x64 position-independent trampoline (56 bytes).
        //
        // The context is stored immediately after the code and found through a
        // RIP-relative LEA. Do not rely on the remote thread's initial RCX: a
        // few launch/injection layers wrap native thread starts and have been
        // observed entering the stub with a damaged argument register.
        // Flow:
        //   1. If fnRtlAddFunctionTable != 0: call it(pdataAddr, pdataCount, imageBase)
        //   2. Call DllMain(imageBase, DLL_PROCESS_ATTACH, NULL)
        //   3. Return 0.
        //
        // x64 ABI: RBX is callee-saved, 0x20 bytes shadow space, stack 16-aligned.
        // ---------------------------------------------------------------------------
        static constexpr uint8_t TRAMPOLINE[] = {
            0x53,                               // push  rbx
            0x48, 0x83, 0xEC, 0x20,             // sub   rsp, 0x20        ; shadow space
            0x48, 0x8D, 0x1D, 0x2C, 0x00, 0x00, 0x00,
                                                // lea   rbx, [rip+0x2c]  ; ctx follows code
            // --- RtlAddFunctionTable(pdataAddr, pdataCount, imageBase) ---
            0x48, 0x8B, 0x03,                   // mov   rax, [rbx]       ; fnRtlAddFunctionTable
            0x48, 0x85, 0xC0,                   // test  rax, rax
            0x74, 0x0D,                         // jz    skip_rtl  (+13 bytes)
            0x48, 0x8B, 0x4B, 0x08,             // mov   rcx, [rbx+0x08]  ; pdataAddr
            0x8B, 0x53, 0x10,                   // mov   edx, [rbx+0x10]  ; pdataCount
            0x4C, 0x8B, 0x43, 0x18,             // mov   r8,  [rbx+0x18]  ; imageBase
            0xFF, 0xD0,                         // call  rax
            // skip_rtl:
            // --- DllMain(imageBase, DLL_PROCESS_ATTACH, NULL) ---
            0x48, 0x8B, 0x4B, 0x18,             // mov   rcx, [rbx+0x18]  ; hInstance = imageBase
            0xBA, 0x01, 0x00, 0x00, 0x00,       // mov   edx, 1           ; DLL_PROCESS_ATTACH
            0x45, 0x33, 0xC0,                   // xor   r8d, r8d          ; lpvReserved = NULL
            0xFF, 0x53, 0x20,                   // call  [rbx+0x20]        ; entryPoint
            0x33, 0xC0,                         // xor   eax, eax          ; return 0
            0x48, 0x83, 0xC4, 0x20,             // add   rsp, 0x20
            0x5B,                               // pop   rbx
            0xC3,                               // ret
        };
        static_assert(sizeof(TRAMPOLINE) == 56, "trampoline size mismatch");
        // ---------------------------------------------------------------------------
        // Import resolution must use the export tables loaded in the target.
        // Translating a local function by RVA is unsafe when an application ships a
        // different build of a runtime DLL (Lunar does this for VCRUNTIME140.dll).
        // ---------------------------------------------------------------------------
        bool is_module_loaded(uint32_t processId, const char* dllName)
        {
            HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, processId);
            if (snap == INVALID_HANDLE_VALUE) return false;
            wchar_t wideName[256] = {};
            MultiByteToWideChar(CP_ACP, 0, dllName, -1, wideName, 256);
            MODULEENTRY32W me = {};
            me.dwSize = sizeof(me);
            bool found = false;
            if (Module32FirstW(snap, &me))
            {
                do
                {
                    if (_wcsicmp(me.szModule, wideName) == 0)
                    {
                        found = true;
                        break;
                    }
                } while (Module32NextW(snap, &me));
            }
            CloseHandle(snap);
            return found;
        }
        void resolve_import_module(const char* name, char* output, size_t outputSize)
        {
            strncpy_s(output, outputSize, name, _TRUNCATE);
            if (_strnicmp(name, "api-ms-", 7) != 0 && _strnicmp(name, "ext-ms-", 7) != 0)
                return;
            HMODULE local = GetModuleHandleA(name);
            if (!local)
                local = LoadLibraryExA(name, nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
            if (!local)
            {
                mm_log("could not resolve API set %s locally", name);
                return;
            }
            char path[MAX_PATH] = {};
            if (!GetModuleFileNameA(local, path, sizeof(path))) return;
            const char* base = strrchr(path, '\\');
            strncpy_s(output, outputSize, base ? base + 1 : path, _TRUNCATE);
            mm_log("API set %s -> %s", name, output);
        }

        bool load_library_remote(HANDLE process, uint32_t processId,
            const char* dllName, const NtApi& nt)
        {
            if (is_module_loaded(processId, dllName)) return true;
            size_t nameLen = strlen(dllName) + 1;
            void* remoteName = nullptr;
            SIZE_T nameSize = nameLen;
            if (nt.AllocateVirtualMemory(process, &remoteName, 0, &nameSize,
                MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE) != 0)
                return false;
            SIZE_T written = 0;
            nt.WriteVirtualMemory(process, remoteName,
                const_cast<char*>(dllName), nameLen, &written);
            auto loadLib = reinterpret_cast<PVOID>(remote_proc(
                process, processId, XORSTRW(L"kernel32.dll"), XORSTR("LoadLibraryA")));
            if (!loadLib)
            {
                SIZE_T freeSize = 0;
                nt.FreeVirtualMemory(process, &remoteName, &freeSize, MEM_RELEASE);
                return false;
            }
            HANDLE thread = nullptr;
            NTSTATUS status = nt.CreateThreadEx(&thread, THREAD_ALL_ACCESS, nullptr,
                process, loadLib, remoteName, 0, 0, 0, 0, nullptr);
            bool ok = false;
            if (status == 0 && thread)
            {
                LARGE_INTEGER timeout;
                timeout.QuadPart = -100000000LL; // 10 seconds
                ok = nt.WaitForSingleObject(thread, FALSE, &timeout) == 0;
                DWORD exitCode = 0;
                if (ok && (!GetExitCodeThread(thread, &exitCode) || exitCode == 0))
                    ok = false;
                CloseHandle(thread);
            }
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteName, &freeSize, MEM_RELEASE);
            return ok;
        }
        bool resolve_imports(HANDLE process, uint32_t processId,
            BYTE* staged, SIZE_T imageSize,
            const IMAGE_NT_HEADERS* ntHeaders,
            const NtApi& nt)
        {
            const auto& opt = ntHeaders->OptionalHeader;
            if (opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].Size == 0)
                return true;
            DWORD importVA = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress;
            if (importVA == 0 || importVA >= imageSize) return true;
            auto* desc = reinterpret_cast<IMAGE_IMPORT_DESCRIPTOR*>(staged + importVA);
            while (desc->Name)
            {
                if (desc->Name >= imageSize) break;
                auto* rawName = reinterpret_cast<const char*>(staged + desc->Name);
                char dllName[256] = {};
                resolve_import_module(rawName, dllName, sizeof(dllName));
                // Ensure it's loaded in the target too
                if (!load_library_remote(process, processId, dllName, nt))
                {
                    mm_log("import module could not be loaded in target: %s", dllName);
                    return false;
                }
                wchar_t wideName[256] = {};
                if (!MultiByteToWideChar(CP_ACP, 0, dllName, -1,
                        wideName, static_cast<int>(_countof(wideName))))
                    return false;
                // Resolve each import against the target module's own export table.
                if (desc->FirstThunk == 0 || desc->FirstThunk >= imageSize) { ++desc; continue; }
                auto* thunk = reinterpret_cast<IMAGE_THUNK_DATA*>(staged + desc->FirstThunk);
                auto* orig = (desc->OriginalFirstThunk && desc->OriginalFirstThunk < imageSize)
                    ? reinterpret_cast<IMAGE_THUNK_DATA*>(staged + desc->OriginalFirstThunk)
                    : thunk;
                while (orig->u1.AddressOfData)
                {
                    uintptr_t remoteProc = 0;
                    const char* importName = nullptr;
                    WORD importOrdinal = 0;
                    if (IMAGE_SNAP_BY_ORDINAL64(orig->u1.Ordinal))
                    {
                        importOrdinal = static_cast<WORD>(IMAGE_ORDINAL64(orig->u1.Ordinal));
                        remoteProc = remote_export(process, processId, wideName, nullptr,
                            importOrdinal, true);
                    }
                    else
                    {
                        if (orig->u1.AddressOfData >= imageSize) break;
                        auto* import = reinterpret_cast<IMAGE_IMPORT_BY_NAME*>(
                            staged + orig->u1.AddressOfData);
                        importName = reinterpret_cast<const char*>(import->Name);
                        remoteProc = remote_export(process, processId, wideName,
                            importName, 0, false);
                    }
                    if (!remoteProc)
                    {
                        if (importName)
                            mm_log("unresolved import %s!%s", dllName, importName);
                        else
                            mm_log("unresolved import %s!#%u", dllName,
                                (unsigned)importOrdinal);
                        return false;
                    }
                    thunk->u1.Function = static_cast<ULONGLONG>(remoteProc);
                    ++thunk;
                    ++orig;
                }
                ++desc;
            }
            return true;
        }
        // ---------------------------------------------------------------------------
        // ---------------------------------------------------------------------------
    } // namespace
    bool manual_map_inject(HANDLE process, uint32_t processId,
        const void* dllData, size_t dllSize)
    {
        mm_log("begin pid=%lu payload=%zu bytes", (unsigned long)processId, dllSize);
        if (!dllData || dllSize < sizeof(IMAGE_DOS_HEADER))
        {
            mm_log("payload missing or too small");
            return false;
        }
        NtApi nt;
        if (!resolve_nt_api(nt))
        {
            mm_log("resolve_nt_api failed");
            return false;
        }

        std::vector<BYTE> raw(dllSize);
        std::memcpy(raw.data(), dllData, dllSize);

        auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(raw.data());
        if (dos->e_magic != IMAGE_DOS_SIGNATURE)
        {
            mm_log("not a PE: e_magic=0x%04X first bytes %02X %02X %02X %02X",
                (unsigned)dos->e_magic, raw[0], raw[1], raw[2], raw[3]);
            return false;
        }
        if (static_cast<size_t>(dos->e_lfanew) + sizeof(IMAGE_NT_HEADERS) > dllSize)
        {
            mm_log("e_lfanew 0x%lX out of range for %zu byte payload",
                (unsigned long)dos->e_lfanew, dllSize);
            return false;
        }
        auto* peRaw = reinterpret_cast<const IMAGE_NT_HEADERS*>(raw.data() + dos->e_lfanew);
        if (peRaw->Signature != IMAGE_NT_SIGNATURE)
        {
            mm_log("bad NT signature 0x%08lX", (unsigned long)peRaw->Signature);
            return false;
        }
        if (peRaw->FileHeader.Machine != IMAGE_FILE_MACHINE_AMD64)
        {
            mm_log("wrong machine 0x%04X (expected AMD64)",
                (unsigned)peRaw->FileHeader.Machine);
            return false;
        }

        SIZE_T imageSize  = peRaw->OptionalHeader.SizeOfImage;
        ULONGLONG prefBase = peRaw->OptionalHeader.ImageBase;
        DWORD  sizeOfHdrs = peRaw->OptionalHeader.SizeOfHeaders;
        DWORD  entryRVA   = peRaw->OptionalHeader.AddressOfEntryPoint;
        WORD   numSections = peRaw->FileHeader.NumberOfSections;
        mm_log("PE ok imageSize=%llu sections=%u entryRVA=0x%lX prefBase=0x%llX",
            (unsigned long long)imageSize, (unsigned)numSections,
            (unsigned long)entryRVA, (unsigned long long)prefBase);

        IMAGE_DATA_DIRECTORY dirReloc   = peRaw->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_BASERELOC];
        IMAGE_DATA_DIRECTORY dirExcept  = peRaw->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXCEPTION];

        // Snapshot section headers before we free raw
        struct SecInfo { DWORD va; DWORD rawOff; DWORD rawSz; };
        std::vector<SecInfo> secs(numSections);
        auto* secHdr = IMAGE_FIRST_SECTION(peRaw);
        for (WORD i = 0; i < numSections; ++i)
            secs[i] = { secHdr[i].VirtualAddress, secHdr[i].PointerToRawData, secHdr[i].SizeOfRawData };

        // Allocate in target — try preferred base, fall back to any
        void* remoteBase = reinterpret_cast<void*>(prefBase);
        SIZE_T allocSize = imageSize;
        NTSTATUS status = nt.AllocateVirtualMemory(process, &remoteBase, 0,
            &allocSize, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
        if (status != 0)
        {
            remoteBase = nullptr;
            allocSize = imageSize;
            status = nt.AllocateVirtualMemory(process, &remoteBase, 0,
                &allocSize, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
            if (status != 0)
            {
                mm_log("image allocation failed status=0x%08lX size=%llu",
                    (unsigned long)status, (unsigned long long)imageSize);
                return false;
            }
        }
        mm_log("image allocated at 0x%p", remoteBase);

        // Build staged image: headers + sections
        std::vector<BYTE> staged(imageSize, 0);
        std::memcpy(staged.data(), raw.data(),
            std::min(static_cast<size_t>(sizeOfHdrs), dllSize));
        for (WORD i = 0; i < numSections; ++i)
        {
            if (secs[i].rawSz == 0) continue;
            if (secs[i].rawOff + secs[i].rawSz > dllSize) continue;
            if (secs[i].va + secs[i].rawSz > imageSize) continue;
            std::memcpy(staged.data() + secs[i].va,
                raw.data() + secs[i].rawOff, secs[i].rawSz);
        }

        // Done with raw copy
        SecureZeroMemory(raw.data(), raw.size());

        // Relocations (staged buffer has its own copy of the headers, but we
        // already extracted everything we need into local vars above)
        auto delta = static_cast<ptrdiff_t>(
            reinterpret_cast<uintptr_t>(remoteBase) - prefBase);
        if (delta != 0 && dirReloc.Size > 0 && dirReloc.VirtualAddress != 0
            && dirReloc.VirtualAddress < imageSize)
        {
            auto* reloc = reinterpret_cast<IMAGE_BASE_RELOCATION*>(
                staged.data() + dirReloc.VirtualAddress);
            auto* relocEnd = reinterpret_cast<BYTE*>(reloc) + dirReloc.Size;
            while (reinterpret_cast<BYTE*>(reloc) + sizeof(IMAGE_BASE_RELOCATION) <= relocEnd
                && reloc->SizeOfBlock >= sizeof(IMAGE_BASE_RELOCATION))
            {
                DWORD count = (reloc->SizeOfBlock - sizeof(IMAGE_BASE_RELOCATION)) / sizeof(WORD);
                auto* entries = reinterpret_cast<WORD*>(reloc + 1);
                for (DWORD j = 0; j < count; ++j)
                {
                    WORD type   = entries[j] >> 12;
                    WORD offset = entries[j] & 0xFFF;
                    DWORD targetRVA = reloc->VirtualAddress + offset;
                    if (type == IMAGE_REL_BASED_DIR64 && targetRVA + 8 <= imageSize)
                    {
                        auto* ptr = reinterpret_cast<ULONGLONG*>(staged.data() + targetRVA);
                        *ptr += static_cast<ULONGLONG>(delta);
                    }
                    else if (type == IMAGE_REL_BASED_HIGHLOW && targetRVA + 4 <= imageSize)
                    {
                        auto* ptr = reinterpret_cast<DWORD*>(staged.data() + targetRVA);
                        *ptr += static_cast<DWORD>(delta);
                    }
                }
                reloc = reinterpret_cast<IMAGE_BASE_RELOCATION*>(
                    reinterpret_cast<BYTE*>(reloc) + reloc->SizeOfBlock);
            }
        }

        // Re-read NT headers from the staged buffer for import resolution
        auto* peStaged = reinterpret_cast<const IMAGE_NT_HEADERS*>(
            staged.data() + reinterpret_cast<const IMAGE_DOS_HEADER*>(staged.data())->e_lfanew);

        if (!resolve_imports(process, processId, staged.data(), imageSize, peStaged, nt))
        {
            mm_log("resolve_imports failed");
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        // Write the fully-resolved image in chunks to avoid partial-write failures
        bool writeOk = true;
        for (SIZE_T off = 0; off < imageSize; )
        {
            SIZE_T chunk = imageSize - off;
            if (chunk > 0x10000) chunk = 0x10000; // 64 KiB
            SIZE_T written = 0;
            status = nt.WriteVirtualMemory(process,
                static_cast<BYTE*>(remoteBase) + off,
                staged.data() + off, chunk, &written);
            if (status != 0) { writeOk = false; break; }
            off += chunk;
        }
        SecureZeroMemory(staged.data(), staged.size());
        if (!writeOk)
        {
            mm_log("image write failed status=0x%08lX", (unsigned long)status);
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        mm_log("image written, entry at 0x%p",
            static_cast<BYTE*>(remoteBase) + entryRVA);

        // Trampoline context
        TrampolineCtx ctx = {};
        ctx.imageBase  = remoteBase;
        ctx.entryPoint = static_cast<BYTE*>(remoteBase) + entryRVA;

        uintptr_t rtlAddr = remote_proc(process, processId,
            XORSTRW(L"kernel32.dll"), XORSTR("RtlAddFunctionTable"));
        if (rtlAddr && dirExcept.Size > 0 && dirExcept.VirtualAddress != 0)
        {
            ctx.fnRtlAddFunctionTable = reinterpret_cast<void*>(rtlAddr);
            ctx.pdataAddr  = static_cast<BYTE*>(remoteBase) + dirExcept.VirtualAddress;
            ctx.pdataCount = dirExcept.Size / static_cast<uint32_t>(sizeof(RUNTIME_FUNCTION));
        }
        mm_log("RtlAddFunctionTable=0x%p pdataCount=%u",
            (void*)rtlAddr, (unsigned)ctx.pdataCount);

        SIZE_T totalSize = sizeof(TRAMPOLINE) + sizeof(TrampolineCtx);
        void* remoteTrampoline = nullptr;
        status = nt.AllocateVirtualMemory(process, &remoteTrampoline, 0,
            &totalSize, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
        if (status != 0)
        {
            mm_log("trampoline allocation failed status=0x%08lX", (unsigned long)status);
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        auto* codeAddr = static_cast<BYTE*>(remoteTrampoline);
        auto* ctxAddr  = codeAddr + sizeof(TRAMPOLINE);
        SIZE_T written = 0;

        status = nt.WriteVirtualMemory(process, codeAddr,
            const_cast<uint8_t*>(TRAMPOLINE), sizeof(TRAMPOLINE), &written);
        if (status != 0)
        {
            mm_log("trampoline code write failed status=0x%08lX", (unsigned long)status);
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        status = nt.WriteVirtualMemory(process, ctxAddr, &ctx, sizeof(ctx), &written);
        if (status != 0)
        {
            mm_log("trampoline context write failed status=0x%08lX", (unsigned long)status);
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        HANDLE thread = nullptr;
        status = nt.CreateThreadEx(&thread, THREAD_ALL_ACCESS, nullptr, process,
            codeAddr, nullptr, 0, 0, 0, 0, nullptr);
        if (status != 0 || !thread)
        {
            mm_log("remote thread creation failed status=0x%08lX", (unsigned long)status);
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        // Only reclaim executable startup memory after Windows confirms the
        // remote thread has exited. A timeout/failure deliberately leaks this
        // tiny allocation in the target rather than racing its instruction
        // pointer and causing an access violation.
        mm_log("remote entry thread started at 0x%p", codeAddr);
        DWORD waitResult = WaitForSingleObject(thread, 15000);
        DWORD entryExit = 0;
        GetExitCodeThread(thread, &entryExit);
        bool success = waitResult == WAIT_OBJECT_0;
        mm_log("entry thread wait=0x%08lX exit=%lu", (unsigned long)waitResult,
            (unsigned long)entryExit);
        CloseHandle(thread);

        if (success)
        {
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
        }
        return success;
    }
} // namespace mindless
