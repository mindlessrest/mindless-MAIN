#pragma once

#include <cstdint>
#include <cstddef>
#include <windows.h>
#include <winternl.h>
#include <intrin.h>

#ifndef _M_X64
#error "mindless::li is x64-only"
#endif

namespace mindless::li
{
    namespace detail
    {

        using hash_t = std::uint32_t;

        struct LDR_DATA_TABLE_ENTRY_
        {
            LIST_ENTRY InLoadOrderLinks;
            LIST_ENTRY InMemoryOrderLinks;
            LIST_ENTRY InInitializationOrderLinks;

            void* DllBase;
            void* EntryPoint;

            ULONG SizeOfImage;

            UNICODE_STRING FullDllName;
            UNICODE_STRING BaseDllName;
        };

        constexpr char ascii_lower(char c) noexcept
        {
            return (c >= 'A' && c <= 'Z')
                ? static_cast<char>(c + ('a' - 'A'))
                : c;
        }

        constexpr wchar_t ascii_lower(wchar_t c) noexcept
        {
            return (c >= L'A' && c <= L'Z')
                ? static_cast<wchar_t>(c + (L'a' - L'A'))
                : c;
        }

        constexpr hash_t hash_str(const char* s) noexcept
        {
            hash_t h = 2166136261u;

            while (*s)
            {
                h ^= static_cast<std::uint8_t>(ascii_lower(*s++));
                h *= 16777619u;
            }

            return h;
        }

        inline hash_t hash_str_runtime(
            const char* s,
            std::size_t len) noexcept
        {
            hash_t h = 2166136261u;

            for (std::size_t i = 0; i < len; ++i)
            {
                h ^= static_cast<std::uint8_t>(ascii_lower(s[i]));
                h *= 16777619u;
            }

            return h;
        }

        inline hash_t hash_wstr(
            const wchar_t* s,
            std::size_t len) noexcept
        {
            hash_t h = 2166136261u;

            for (std::size_t i = 0; i < len; ++i)
            {
                const auto c = ascii_lower(s[i]);

                // Module names are ASCII in practice, but hash both bytes
                // so the function behaves consistently for wchar_t input.
                h ^= static_cast<std::uint8_t>(c & 0xFF);
                h *= 16777619u;

                h ^= static_cast<std::uint8_t>((c >> 8) & 0xFF);
                h *= 16777619u;
            }

            return h;
        }

        inline PEB* get_peb() noexcept
        {
            return reinterpret_cast<PEB*>(__readgsqword(0x60));
        }

        inline void* find_module(hash_t wanted) noexcept
        {
            auto* peb = get_peb();

            if (!peb || !peb->Ldr)
                return nullptr;

            auto* head = &peb->Ldr->InMemoryOrderModuleList;

            for (auto* node = head->Flink;
                node && node != head;
                node = node->Flink)
            {
                // node points at InMemoryOrderLinks, not the beginning
                // of LDR_DATA_TABLE_ENTRY_.
                auto* entry =
                    CONTAINING_RECORD(
                        node,
                        LDR_DATA_TABLE_ENTRY_,
                        InMemoryOrderLinks);

                if (!entry->DllBase ||
                    !entry->BaseDllName.Buffer ||
                    !entry->BaseDllName.Length)
                {
                    continue;
                }

                const auto length =
                    static_cast<std::size_t>(
                        entry->BaseDllName.Length /
                        sizeof(wchar_t));

                if (hash_wstr(entry->BaseDllName.Buffer, length) == wanted)
                    return entry->DllBase;
            }

            return nullptr;
        }

        struct image_view
        {
            std::uint8_t* base{};
            std::size_t size{};

            IMAGE_NT_HEADERS64* nt{};
            IMAGE_EXPORT_DIRECTORY* exports{};

            DWORD export_rva{};
            DWORD export_size{};

            DWORD* functions{};
            DWORD* names{};
            WORD* ordinals{};

            explicit operator bool() const noexcept
            {
                return base && nt && exports;
            }
        };

        inline bool valid_range(
            std::size_t image_size,
            std::size_t offset,
            std::size_t length) noexcept
        {
            return
                offset <= image_size &&
                length <= image_size - offset;
        }

        inline image_view get_image(void* module) noexcept
        {
            image_view result{};

            if (!module)
                return result;

            auto* base =
                static_cast<std::uint8_t*>(module);

            auto* dos =
                reinterpret_cast<IMAGE_DOS_HEADER*>(base);

            if (dos->e_magic != IMAGE_DOS_SIGNATURE)
                return result;

            if (dos->e_lfanew <= 0)
                return result;

            auto* nt =
                reinterpret_cast<IMAGE_NT_HEADERS64*>(
                    base + dos->e_lfanew);

            if (nt->Signature != IMAGE_NT_SIGNATURE)
                return result;

            if (nt->OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC)
                return result;

            const auto image_size =
                static_cast<std::size_t>(
                    nt->OptionalHeader.SizeOfImage);

            if (!image_size)
                return result;

            const auto& directory =
                nt->OptionalHeader
                .DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT];

            if (!directory.VirtualAddress ||
                directory.Size < sizeof(IMAGE_EXPORT_DIRECTORY))
            {
                return result;
            }

            if (!valid_range(
                image_size,
                directory.VirtualAddress,
                directory.Size))
            {
                return result;
            }

            auto* exports =
                reinterpret_cast<IMAGE_EXPORT_DIRECTORY*>(
                    base + directory.VirtualAddress);

            if (!exports->AddressOfFunctions ||
                !exports->NumberOfFunctions)
            {
                return result;
            }

            const auto function_bytes =
                static_cast<std::size_t>(
                    exports->NumberOfFunctions) *
                sizeof(DWORD);

            if (!valid_range(
                image_size,
                exports->AddressOfFunctions,
                function_bytes))
            {
                return result;
            }

            DWORD* names = nullptr;
            WORD* ordinals = nullptr;

            if (exports->NumberOfNames)
            {
                const auto name_bytes =
                    static_cast<std::size_t>(
                        exports->NumberOfNames) *
                    sizeof(DWORD);

                const auto ordinal_bytes =
                    static_cast<std::size_t>(
                        exports->NumberOfNames) *
                    sizeof(WORD);

                if (!valid_range(
                    image_size,
                    exports->AddressOfNames,
                    name_bytes) ||
                    !valid_range(
                        image_size,
                        exports->AddressOfNameOrdinals,
                        ordinal_bytes))
                {
                    return result;
                }

                names =
                    reinterpret_cast<DWORD*>(
                        base + exports->AddressOfNames);

                ordinals =
                    reinterpret_cast<WORD*>(
                        base + exports->AddressOfNameOrdinals);
            }

            result.base = base;
            result.size = image_size;
            result.nt = nt;
            result.exports = exports;

            result.export_rva = directory.VirtualAddress;
            result.export_size = directory.Size;

            result.functions =
                reinterpret_cast<DWORD*>(
                    base + exports->AddressOfFunctions);

            result.names = names;
            result.ordinals = ordinals;

            return result;
        }

        inline bool is_forwarder(
            const image_view& image,
            DWORD rva) noexcept
        {
            const auto begin =
                static_cast<std::uint64_t>(
                    image.export_rva);

            const auto end =
                begin +
                static_cast<std::uint64_t>(
                    image.export_size);

            return
                static_cast<std::uint64_t>(rva) >= begin &&
                static_cast<std::uint64_t>(rva) < end;
        }

        inline void* find_export(
            void* module,
            hash_t wanted) noexcept
        {
            const auto image = get_image(module);

            if (!image || !image.names || !image.ordinals)
                return nullptr;

            for (DWORD i = 0;
                i < image.exports->NumberOfNames;
                ++i)
            {
                const DWORD name_rva = image.names[i];

                if (!valid_range(image.size, name_rva, 1))
                    continue;

                const char* name =
                    reinterpret_cast<const char*>(
                        image.base + name_rva);

                // Ensure the export name terminates inside the image.
                std::size_t length = 0;

                while (name_rva + length < image.size &&
                    name[length] != '\0')
                {
                    ++length;
                }

                if (name_rva + length >= image.size)
                    continue;

                if (hash_str_runtime(name, length) != wanted)
                    continue;

                const WORD ordinal_index =
                    image.ordinals[i];

                if (ordinal_index >=
                    image.exports->NumberOfFunctions)
                {
                    return nullptr;
                }

                const DWORD function_rva =
                    image.functions[ordinal_index];

                if (!function_rva)
                    return nullptr;

                // Forwarded exports require resolving another DLL/export.
                // Don't return the forwarding string as executable code.
                if (is_forwarder(image, function_rva))
                    return nullptr;

                if (!valid_range(
                    image.size,
                    function_rva,
                    1))
                {
                    return nullptr;
                }

                return image.base + function_rva;
            }

            return nullptr;
        }

        template<typename F>
        inline F resolve(
            hash_t module_hash,
            hash_t function_hash) noexcept
        {
            auto* module =
                find_module(module_hash);

            if (!module)
                return nullptr;

            return reinterpret_cast<F>(
                find_export(module, function_hash));
        }

    } // namespace detail
} // namespace mindless::li

#define LI_HASH(s) \
    (::mindless::li::detail::hash_str(s))

#define LI_FN(mod, fn)                                             \
    (::mindless::li::detail::resolve<decltype(&fn)>(               \
        LI_HASH(#mod),                                             \
        LI_HASH(#fn)))