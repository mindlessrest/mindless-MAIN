#pragma once

// Minimal lazy importer — resolves functions at runtime by walking PEB,
// so they never appear in the IAT.

#include <cstdint>
#include <windows.h>
#include <winternl.h>
#include <intrin.h>

namespace mindless::li
{

namespace detail
{

struct PEB_LDR_DATA_ {
    unsigned long Length;
    unsigned long Initialized;
    void* SsHandle;
    LIST_ENTRY InLoadOrderModuleList;
};

struct LDR_DATA_TABLE_ENTRY_ {
    LIST_ENTRY InLoadOrderLinks;
    LIST_ENTRY InMemoryOrderLinks;
    LIST_ENTRY InInitializationOrderLinks;
    void* DllBase;
    void* EntryPoint;
    unsigned long SizeOfImage;
    UNICODE_STRING FullDllName;
    UNICODE_STRING BaseDllName;
};

constexpr unsigned long hash_str(const char* s)
{
    unsigned long h = 2166136261u;
    while (*s)
    {
        char c = *s++;
        if (c >= 'A' && c <= 'Z') c += 32;
        h ^= static_cast<unsigned long>(c);
        h *= 16777619u;
    }
    return h;
}

constexpr unsigned long hash_wstr(const wchar_t* s, unsigned long len)
{
    unsigned long h = 2166136261u;
    for (unsigned long i = 0; i < len; ++i)
    {
        wchar_t c = s[i];
        if (c >= L'A' && c <= L'Z') c += 32;
        h ^= static_cast<unsigned long>(c);
        h *= 16777619u;
    }
    return h;
}

inline void* get_peb()
{
#ifdef _M_X64
    return reinterpret_cast<void*>(__readgsqword(0x60));
#else
    return reinterpret_cast<void*>(__readfsdword(0x30));
#endif
}

inline void* find_module(unsigned long hash)
{
    auto* peb = static_cast<unsigned char*>(get_peb());
#ifdef _M_X64
    auto* ldr = *reinterpret_cast<PEB_LDR_DATA_**>(peb + 0x18);
#else
    auto* ldr = *reinterpret_cast<PEB_LDR_DATA_**>(peb + 0x0C);
#endif
    auto* head = &ldr->InLoadOrderModuleList;
    for (auto* entry = head->Flink; entry != head; entry = entry->Flink)
    {
        auto* mod = reinterpret_cast<LDR_DATA_TABLE_ENTRY_*>(entry);
        if (!mod->DllBase) continue;
        unsigned long h = hash_wstr(mod->BaseDllName.Buffer,
            mod->BaseDllName.Length / sizeof(wchar_t));
        if (h == hash) return mod->DllBase;
    }
    return nullptr;
}

inline void* find_export(void* module_base, unsigned long hash)
{
    auto* dos = static_cast<unsigned char*>(module_base);
    auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(
        dos + reinterpret_cast<IMAGE_DOS_HEADER*>(dos)->e_lfanew);
    auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT];
    if (dir.Size == 0) return nullptr;

    auto* exp = reinterpret_cast<IMAGE_EXPORT_DIRECTORY*>(dos + dir.VirtualAddress);
    auto* names = reinterpret_cast<unsigned long*>(dos + exp->AddressOfNames);
    auto* ords = reinterpret_cast<unsigned short*>(dos + exp->AddressOfNameOrdinals);
    auto* funcs = reinterpret_cast<unsigned long*>(dos + exp->AddressOfFunctions);

    for (unsigned long i = 0; i < exp->NumberOfNames; ++i)
    {
        auto* name = reinterpret_cast<const char*>(dos + names[i]);
        if (hash_str(name) == hash)
            return dos + funcs[ords[i]];
    }
    return nullptr;
}

template<typename F>
F resolve(unsigned long mod_hash, unsigned long fn_hash)
{
    void* mod = find_module(mod_hash);
    if (!mod) return nullptr;
    return reinterpret_cast<F>(find_export(mod, fn_hash));
}

} // namespace detail

} // namespace mindless::li

// LI_FN(Module, Function) — resolves at runtime, never touches IAT
// Usage: LI_FN(kernel32.dll, VirtualAlloc)(nullptr, size, MEM_COMMIT, PAGE_READWRITE);
#define LI_HASH(s) (::mindless::li::detail::hash_str(s))

#define LI_FN(mod, fn) \
    reinterpret_cast<decltype(&fn)>( \
        ::mindless::li::detail::find_export( \
            ::mindless::li::detail::find_module(LI_HASH(#mod)), \
            LI_HASH(#fn)))
