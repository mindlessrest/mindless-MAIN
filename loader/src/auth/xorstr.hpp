#pragma once

#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace mindless::detail
{

    constexpr std::uint64_t mix64(std::uint64_t x) noexcept
    {
        x ^= x >> 30;
        x *= 0xBF58476D1CE4E5B9ULL;
        x ^= x >> 27;
        x *= 0x94D049BB133111EBULL;
        x ^= x >> 31;
        return x;
    }

    consteval std::uint64_t hash_file(const char* s) noexcept
    {
        std::uint64_t h = 0xCBF29CE484222325ULL;

        while (*s)
        {
            h ^= static_cast<std::uint8_t>(*s++);
            h *= 0x100000001B3ULL;
        }

        return mix64(h);
    }

    constexpr std::uint64_t stream(
        std::uint64_t seed,
        std::size_t index) noexcept
    {
        return mix64(
            seed +
            0x9E3779B97F4A7C15ULL *
            (static_cast<std::uint64_t>(index) + 1ULL));
    }

    template<typename T>
    constexpr T key_for(
        std::uint64_t seed,
        std::size_t index) noexcept
    {
        using U = std::make_unsigned_t<T>;

        std::uint64_t x = stream(seed, index);

        if constexpr (sizeof(U) == 1)
        {
            x ^= x >> 8;
            x ^= x >> 16;
            x ^= x >> 32;
        }
        else if constexpr (sizeof(U) == 2)
        {
            x ^= x >> 16;
            x ^= x >> 32;
        }
        else
        {
            x ^= x >> 32;
        }

        return static_cast<T>(static_cast<U>(x));
    }

    template<typename T>
    inline void secure_zero(T* data, std::size_t count) noexcept
    {
        volatile T* p = data;

        while (count--)
            *p++ = T{};
    }

    template<typename CharT, std::size_t N, std::uint64_t Seed>
    class CryptString
    {
        using U = std::make_unsigned_t<CharT>;

    public:
        consteval CryptString(const CharT(&str)[N]) noexcept
        {
            for (std::size_t i = 0; i < N; ++i)
            {
                const U value = static_cast<U>(str[i]);
                const U key = static_cast<U>(key_for<U>(Seed, i));

                encrypted_[i] = static_cast<CharT>(value ^ key);
            }
        }

        CryptString(const CryptString&) = delete;
        CryptString& operator=(const CryptString&) = delete;

        CryptString(CryptString&&) = default;
        CryptString& operator=(CryptString&&) = default;

        ~CryptString()
        {
            clear();
            secure_zero(encrypted_, N);
        }

        const CharT* decrypt() const noexcept
        {
            for (std::size_t i = 0; i < N; ++i)
            {
                const U value = static_cast<U>(encrypted_[i]);
                const U key = static_cast<U>(key_for<U>(Seed, i));

                buffer_[i] = static_cast<CharT>(value ^ key);
            }

            return buffer_;
        }

        void clear() const noexcept
        {
            secure_zero(buffer_, N);
        }

        constexpr std::size_t size() const noexcept
        {
            return N - 1;
        }

    private:
        CharT encrypted_[N]{};
        mutable CharT buffer_[N]{};
    };

    template<std::uint64_t Seed, typename CharT, std::size_t N>
    consteval auto make_crypt(const CharT(&str)[N]) noexcept
    {
        return CryptString<CharT, N, Seed>(str);
    }

} // namespace mindless::detail


#define MINDLESS_CRYPT_SEED(counter)                                      \
    (::mindless::detail::mix64(                                           \
        ::mindless::detail::hash_file(__FILE__) ^                         \
        (static_cast<std::uint64_t>(__LINE__) *                           \
         0x9E3779B97F4A7C15ULL) ^                                        \
        (static_cast<std::uint64_t>(counter) *                            \
         0xD1B54A32D192ED03ULL)))

#define MINDLESS_CRYPT_IMPL(s, counter)                                   \
    ([]() -> const auto*                                                  \
    {                                                                     \
        static auto instance =                                            \
            ::mindless::detail::make_crypt<                               \
                MINDLESS_CRYPT_SEED(counter)>(s);                         \
                                                                          \
        return instance.decrypt();                                        \
    }())

#define XORSTR(s)  MINDLESS_CRYPT_IMPL(s, __COUNTER__)
#define XORSTRW(s) MINDLESS_CRYPT_IMPL(s, __COUNTER__)
