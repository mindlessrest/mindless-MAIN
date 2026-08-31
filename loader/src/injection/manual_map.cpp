#include "manual_map.hpp"
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
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    if (!ntdll) return false;
    api.AllocateVirtualMemory = (NtAllocateVirtualMemory_t)
        GetProcAddress(ntdll, "NtAllocateVirtualMemory");
    api.WriteVirtualMemory = (NtWriteVirtualMemory_t)
        GetProcAddress(ntdll, "NtWriteVirtualMemory");
    api.FreeVirtualMemory = (NtFreeVirtualMemory_t)
        GetProcAddress(ntdll, "NtFreeVirtualMemory");
    api.CreateThreadEx = (NtCreateThreadEx_t)
        GetProcAddress(ntdll, "NtCreateThreadEx");
    api.WaitForSingleObject = (NtWaitForSingleObject_t)
        GetProcAddress(ntdll, "NtWaitForSingleObject");
    return api.AllocateVirtualMemory && api.WriteVirtualMemory
        && api.FreeVirtualMemory && api.CreateThreadEx && api.WaitForSingleObject;
}

// XOR key derived from compile-time constants to make static analysis harder
static constexpr uint8_t XOR_KEY[] = {
    0x4D, 0x1A, 0xE7, 0x93, 0x5F, 0xC2, 0x38, 0xAB,
    0x6D, 0xF0, 0x14, 0x87, 0x2E, 0xB5, 0x71, 0xD9,
    0x03, 0x8C, 0x46, 0xFA, 0x65, 0x29, 0xDE, 0xB0,
    0x57, 0xC8, 0x1F, 0xA3, 0x74, 0xE1, 0x9B, 0x42,
};
static constexpr size_t XOR_KEY_LEN = sizeof(XOR_KEY);

void xor_transform(void* data, size_t size)
{
    auto* bytes = static_cast<uint8_t*>(data);
    for (size_t i = 0; i < size; ++i)
        bytes[i] ^= XOR_KEY[i % XOR_KEY_LEN];
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
// Handcrafted x64 position-independent trampoline (52 bytes).
//
// Thread start receives TrampolineCtx* in RCX.  Flow:
//   1. If fnRtlAddFunctionTable != 0: call it(pdataAddr, pdataCount, imageBase)
//   2. Call DllMain(imageBase, DLL_PROCESS_ATTACH, NULL)
//   3. Return 0.
//
// x64 ABI: RBX is callee-saved, 0x20 bytes shadow space, stack 16-aligned.
// ---------------------------------------------------------------------------
static constexpr uint8_t TRAMPOLINE[] = {
    0x53,                               // push  rbx
    0x48, 0x83, 0xEC, 0x20,             // sub   rsp, 0x20        ; shadow space
    0x48, 0x89, 0xCB,                   // mov   rbx, rcx         ; rbx = ctx

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
static_assert(sizeof(TRAMPOLINE) == 52, "trampoline size mismatch");

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
        }
        while (Module32NextW(snap, &me));
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

    auto loadLib = reinterpret_cast<PVOID>(
        GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryA"));

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
            if (!proc) return false;
            thunk->u1.Function = reinterpret_cast<ULONGLONG>(proc);
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
uintptr_t remote_ntdll_proc(uint32_t processId, const char* procName)
{
    HMODULE localNtdll = GetModuleHandleW(L"ntdll.dll");
    FARPROC localProc = localNtdll ? GetProcAddress(localNtdll, procName) : nullptr;
    if (!localProc) return 0;

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, processId);
    if (snap == INVALID_HANDLE_VALUE) return 0;

    MODULEENTRY32W me = {};
    me.dwSize = sizeof(me);
    uintptr_t remoteBase = 0;
    if (Module32FirstW(snap, &me))
    {
        do
        {
            if (_wcsicmp(me.szModule, L"ntdll.dll") == 0)
            {
                remoteBase = reinterpret_cast<uintptr_t>(me.modBaseAddr);
                break;
            }
        }
        while (Module32NextW(snap, &me));
    }
    CloseHandle(snap);
    if (!remoteBase) return 0;

    return remoteBase + (reinterpret_cast<uintptr_t>(localProc)
                       - reinterpret_cast<uintptr_t>(localNtdll));
}

} // namespace

bool manual_map_inject(HANDLE process, uint32_t processId,
                       const void* dllData, size_t dllSize)
{
    if (!dllData || dllSize < sizeof(IMAGE_DOS_HEADER)) return false;

    NtApi nt;
    if (!resolve_nt_api(nt)) return false;

    // Decrypt the XOR-encrypted resource into a local buffer
    std::vector<BYTE> decrypted(dllSize);
    std::memcpy(decrypted.data(), dllData, dllSize);
    //xor_transform(decrypted.data(), dllSize); uhhh??

    auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(decrypted.data());
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) 
        return false;
    if (static_cast<size_t>(dos->e_lfanew) + sizeof(IMAGE_NT_HEADERS) > dllSize) 
        return false;

    auto* ntHeaders = reinterpret_cast<const IMAGE_NT_HEADERS*>(
        decrypted.data() + dos->e_lfanew);
    if (ntHeaders->Signature != IMAGE_NT_SIGNATURE) return false;
    if (ntHeaders->FileHeader.Machine != IMAGE_FILE_MACHINE_AMD64) return false;

    const auto& opt = ntHeaders->OptionalHeader;
    SIZE_T imageSize = opt.SizeOfImage;

    // Allocate image memory via NtAllocateVirtualMemory
    void* remoteBase = reinterpret_cast<void*>(opt.ImageBase);
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

    // Build local staging buffer with headers + sections
    std::vector<BYTE> staged(imageSize, 0);
    std::memcpy(staged.data(), decrypted.data(),
        std::min(static_cast<size_t>(opt.SizeOfHeaders), dllSize));

    auto* sections = IMAGE_FIRST_SECTION(ntHeaders);
    for (WORD i = 0; i < ntHeaders->FileHeader.NumberOfSections; ++i)
    {
        if (sections[i].SizeOfRawData == 0) continue;
        if (sections[i].PointerToRawData + sections[i].SizeOfRawData > dllSize) continue;
        if (sections[i].VirtualAddress + sections[i].SizeOfRawData > imageSize) continue;
        std::memcpy(staged.data() + sections[i].VirtualAddress,
            decrypted.data() + sections[i].PointerToRawData,
            sections[i].SizeOfRawData);
    }

    // Wipe decrypted PE from local memory
    SecureZeroMemory(decrypted.data(), decrypted.size());

    // Process relocations locally
    auto delta = static_cast<ptrdiff_t>(
        reinterpret_cast<uintptr_t>(remoteBase) - opt.ImageBase);
    if (delta != 0 && opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_BASERELOC].Size > 0)
    {
        auto* reloc = reinterpret_cast<IMAGE_BASE_RELOCATION*>(
            staged.data() + opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_BASERELOC].VirtualAddress);
        while (reloc->VirtualAddress)
        {
            DWORD count = (reloc->SizeOfBlock - sizeof(IMAGE_BASE_RELOCATION)) / sizeof(WORD);
            auto* entries = reinterpret_cast<WORD*>(reloc + 1);
            for (DWORD j = 0; j < count; ++j)
            {
                WORD type = entries[j] >> 12;
                WORD offset = entries[j] & 0xFFF;
                if (type == IMAGE_REL_BASED_DIR64)
                {
                    auto* ptr = reinterpret_cast<ULONGLONG*>(
                        staged.data() + reloc->VirtualAddress + offset);
                    *ptr += static_cast<ULONGLONG>(delta);
                }
                else if (type == IMAGE_REL_BASED_HIGHLOW)
                {
                    auto* ptr = reinterpret_cast<DWORD*>(
                        staged.data() + reloc->VirtualAddress + offset);
                    *ptr += static_cast<DWORD>(delta);
                }
            }
            reloc = reinterpret_cast<IMAGE_BASE_RELOCATION*>(
                reinterpret_cast<BYTE*>(reloc) + reloc->SizeOfBlock);
        }
    }

    // Resolve imports from the injector side — system DLLs share the same
    // base address across all processes in a Windows boot session, so local
    // GetProcAddress results are valid in the target.
    if (!resolve_imports(process, processId, staged.data(), imageSize, ntHeaders, nt))
    {
        SIZE_T freeSize = 0;
        nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
        return false;
    }

    // Write the fully-resolved image via NtWriteVirtualMemory
    SIZE_T written = 0;
    status = nt.WriteVirtualMemory(process, remoteBase,
        staged.data(), imageSize, &written);
    SecureZeroMemory(staged.data(), staged.size());
    if (status != 0 || written != imageSize)
    {
        SIZE_T freeSize = 0;
        nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
        return false;
    }

    // Prepare the trampoline context — only needs RtlAddFunctionTable + DllMain
    TrampolineCtx ctx = {};
    ctx.imageBase = remoteBase;
    ctx.entryPoint = static_cast<BYTE*>(remoteBase) + opt.AddressOfEntryPoint;

    uintptr_t rtlAddr = remote_ntdll_proc(processId, "RtlAddFunctionTable");
    if (rtlAddr
        && opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXCEPTION].Size > 0
        && opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXCEPTION].VirtualAddress != 0)
    {
        ctx.fnRtlAddFunctionTable = reinterpret_cast<void*>(rtlAddr);
        ctx.pdataAddr = static_cast<BYTE*>(remoteBase)
            + opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXCEPTION].VirtualAddress;
        ctx.pdataCount = opt.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXCEPTION].Size
            / static_cast<uint32_t>(sizeof(RUNTIME_FUNCTION));
    }

    // Allocate remote memory for trampoline context + code (52 bytes + 40 bytes)
    SIZE_T totalSize = sizeof(TrampolineCtx) + sizeof(TRAMPOLINE);
    void* remoteTrampoline = nullptr;
    status = nt.AllocateVirtualMemory(process, &remoteTrampoline, 0,
        &totalSize, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (status != 0)
    {
        SIZE_T freeSize = 0;
        nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
        return false;
    }

    auto* ctxAddr = static_cast<BYTE*>(remoteTrampoline);
    auto* codeAddr = ctxAddr + sizeof(TrampolineCtx);

    // Write context struct
    status = nt.WriteVirtualMemory(process, ctxAddr, &ctx, sizeof(ctx), &written);
    if (status != 0)
    {
        SIZE_T freeSize = 0;
        nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
        nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
        return false;
    }

    // Write trampoline code
    status = nt.WriteVirtualMemory(process, codeAddr,
        const_cast<uint8_t*>(TRAMPOLINE), sizeof(TRAMPOLINE), &written);
    if (status != 0)
    {
        SIZE_T freeSize = 0;
        nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
        nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
        return false;
    }

    // Execute via NtCreateThreadEx — start at trampoline, pass context pointer
    HANDLE thread = nullptr;
    status = nt.CreateThreadEx(&thread, THREAD_ALL_ACCESS, nullptr, process,
        codeAddr, ctxAddr, 0, 0, 0, 0, nullptr);
    if (status != 0 || !thread)
    {
        SIZE_T freeSize = 0;
        nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);
        nt.FreeVirtualMemory(process, &remoteBase, &freeSize, MEM_RELEASE);
        return false;
    }

    LARGE_INTEGER timeout;
    timeout.QuadPart = -150000000LL; // 15 seconds
    bool success = nt.WaitForSingleObject(thread, FALSE, &timeout) == 0;
    CloseHandle(thread);

    // Clean up trampoline memory (image stays — it's the loaded DLL)
    SIZE_T freeSize = 0;
    nt.FreeVirtualMemory(process, &remoteTrampoline, &freeSize, MEM_RELEASE);

    return success;
}

} // namespace mindless
