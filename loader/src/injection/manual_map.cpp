#include "manual_map.hpp"
#include "auth/xorstr.hpp"
#include <TlHelp32.h>
#include <cstring>
#include <vector>
namespace mindless
{
    namespace
    {
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

        uintptr_t find_remote_module_base(uint32_t processId, const wchar_t* moduleName)
        {
            HANDLE snap = CreateToolhelp32Snapshot(
                TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, processId);
            if (snap == INVALID_HANDLE_VALUE) return 0;

            MODULEENTRY32W me = {};
            me.dwSize = sizeof(me);
            uintptr_t result = 0;
            if (Module32FirstW(snap, &me))
            {
                do
                {
                    if (_wcsicmp(me.szModule, moduleName) == 0)
                    {
                        result = reinterpret_cast<uintptr_t>(me.modBaseAddr);
                        break;
                    }
                } while (Module32NextW(snap, &me));
            }
            CloseHandle(snap);
            return result;
        }

        // GetProcAddress can return an address owned by a forwarding module
        // (for example KernelBase or ucrtbase), not by the HMODULE passed to
        // it. Translate through the function's real owner and the target's
        // corresponding module base. Copying a local absolute address into a
        // different process is invalid when ASLR bases differ.
        uintptr_t remote_address_for_local_proc(uint32_t processId, FARPROC localProc)
        {
            if (!localProc) return 0;

            MEMORY_BASIC_INFORMATION memory = {};
            if (VirtualQuery(reinterpret_cast<const void*>(localProc),
                    &memory, sizeof(memory)) != sizeof(memory)
                || !memory.AllocationBase)
                return 0;

            auto localOwner = static_cast<HMODULE>(memory.AllocationBase);
            wchar_t ownerPath[MAX_PATH] = {};
            DWORD pathLength = GetModuleFileNameW(
                localOwner, ownerPath, MAX_PATH);
            if (pathLength == 0 || pathLength >= MAX_PATH) return 0;

            const wchar_t* ownerName = wcsrchr(ownerPath, L'\\');
            ownerName = ownerName ? ownerName + 1 : ownerPath;
            uintptr_t remoteOwner = find_remote_module_base(processId, ownerName);
            if (!remoteOwner) return 0;

            return remoteOwner
                + (reinterpret_cast<uintptr_t>(localProc)
                    - reinterpret_cast<uintptr_t>(localOwner));
        }

        uintptr_t remote_proc(uint32_t processId, const wchar_t* moduleName,
            const char* procName);
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
        // Import resolution from the injector process.
        //
        // On Windows, system DLLs (kernel32, ntdll, user32, ...) are mapped at the
        // same base address in every process within a boot session.  We exploit this
        // by calling LoadLibraryA / GetProcAddress *locally* — the returned addresses
        // are valid in the target process too.
        //
        // For any DLL not yet loaded in the target, we first load it there via a
        // remote NtCreateThreadEx → LoadLibraryA call.
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
                processId, XORSTRW(L"kernel32.dll"), XORSTR("LoadLibraryA")));
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
                auto* dllName = reinterpret_cast<const char*>(staged + desc->Name);
                // Load locally — system DLLs share the same base across processes
                HMODULE localMod = LoadLibraryA(dllName);
                if (!localMod) return false;
                // Ensure it's loaded in the target too
                if (!load_library_remote(process, processId, dllName, nt))
                    return false;
                // Resolve each import using local GetProcAddress
                if (desc->FirstThunk == 0 || desc->FirstThunk >= imageSize) { ++desc; continue; }
                auto* thunk = reinterpret_cast<IMAGE_THUNK_DATA*>(staged + desc->FirstThunk);
                auto* orig = (desc->OriginalFirstThunk && desc->OriginalFirstThunk < imageSize)
                    ? reinterpret_cast<IMAGE_THUNK_DATA*>(staged + desc->OriginalFirstThunk)
                    : thunk;
                while (orig->u1.AddressOfData)
                {
                    FARPROC proc = nullptr;
                    if (IMAGE_SNAP_BY_ORDINAL64(orig->u1.Ordinal))
                    {
                        proc = GetProcAddress(localMod,
                            reinterpret_cast<LPCSTR>(IMAGE_ORDINAL64(orig->u1.Ordinal)));
                    }
                    else
                    {
                        if (orig->u1.AddressOfData >= imageSize) break;
                        auto* import = reinterpret_cast<IMAGE_IMPORT_BY_NAME*>(
                            staged + orig->u1.AddressOfData);
                        proc = GetProcAddress(localMod, import->Name);
                    }
                    uintptr_t remoteProc = remote_address_for_local_proc(processId, proc);
                    if (!remoteProc) return false;
                    thunk->u1.Function = static_cast<ULONGLONG>(remoteProc);
                    ++thunk;
                    ++orig;
                }
                ++desc;
            }
            return true;
        }
        // ---------------------------------------------------------------------------
        // Find RtlAddFunctionTable in the target process's ntdll.  We need the
        // *remote* address because RtlAddFunctionTable modifies process-local state.
        // ---------------------------------------------------------------------------
        uintptr_t remote_proc(uint32_t processId, const wchar_t* moduleName, const char* procName)
        {
            HMODULE localMod = GetModuleHandleW(moduleName);
            FARPROC localProc = localMod ? GetProcAddress(localMod, procName) : nullptr;
            return remote_address_for_local_proc(processId, localProc);
        }
    } // namespace
    bool manual_map_inject(HANDLE process, uint32_t processId,
        const void* dllData, size_t dllSize)
    {
        if (!dllData || dllSize < sizeof(IMAGE_DOS_HEADER)) return false;
        NtApi nt;
        if (!resolve_nt_api(nt)) return false;

        std::vector<BYTE> raw(dllSize);
        std::memcpy(raw.data(), dllData, dllSize);

        auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(raw.data());
        if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
        if (static_cast<size_t>(dos->e_lfanew) + sizeof(IMAGE_NT_HEADERS) > dllSize)
            return false;
        auto* peRaw = reinterpret_cast<const IMAGE_NT_HEADERS*>(raw.data() + dos->e_lfanew);
        if (peRaw->Signature != IMAGE_NT_SIGNATURE) return false;
        if (peRaw->FileHeader.Machine != IMAGE_FILE_MACHINE_AMD64) return false;

        SIZE_T imageSize  = peRaw->OptionalHeader.SizeOfImage;
        ULONGLONG prefBase = peRaw->OptionalHeader.ImageBase;
        DWORD  sizeOfHdrs = peRaw->OptionalHeader.SizeOfHeaders;
        DWORD  entryRVA   = peRaw->OptionalHeader.AddressOfEntryPoint;
        WORD   numSections = peRaw->FileHeader.NumberOfSections;

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
            if (status != 0) return false;
        }

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
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        // Trampoline context
        TrampolineCtx ctx = {};
        ctx.imageBase  = remoteBase;
        ctx.entryPoint = static_cast<BYTE*>(remoteBase) + entryRVA;

        uintptr_t rtlAddr = remote_proc(processId, XORSTRW(L"kernel32.dll"), XORSTR("RtlAddFunctionTable"));
        if (rtlAddr && dirExcept.Size > 0 && dirExcept.VirtualAddress != 0)
        {
            ctx.fnRtlAddFunctionTable = reinterpret_cast<void*>(rtlAddr);
            ctx.pdataAddr  = static_cast<BYTE*>(remoteBase) + dirExcept.VirtualAddress;
            ctx.pdataCount = dirExcept.Size / static_cast<uint32_t>(sizeof(RUNTIME_FUNCTION));
        }

        SIZE_T totalSize = sizeof(TRAMPOLINE) + sizeof(TrampolineCtx);
        void* remoteTrampoline = nullptr;
        status = nt.AllocateVirtualMemory(process, &remoteTrampoline, 0,
            &totalSize, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
        if (status != 0)
        {
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
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        status = nt.WriteVirtualMemory(process, ctxAddr, &ctx, sizeof(ctx), &written);
        if (status != 0)
        {
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
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
            nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
            return false;
        }

        // Only reclaim executable startup memory after Windows confirms the
        // remote thread has exited. A timeout/failure deliberately leaks this
        // tiny allocation in the target rather than racing its instruction
        // pointer and causing an access violation.
        bool success = WaitForSingleObject(thread, 15000) == WAIT_OBJECT_0;
        CloseHandle(thread);

        if (success)
        {
            SIZE_T freeSize = 0;
            nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
        }
        return success;
    }
} // namespace mindless
