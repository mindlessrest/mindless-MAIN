#pragma once
#include <cstddef>
#include <cstdint>

namespace mindless
{

namespace detail
{

constexpr uint64_t xorstr_seed()
{
    uint64_t h = 0;
    for (const char* p = __TIME__ __DATE__; *p; ++p)
        h = h * 131 + static_cast<uint64_t>(*p);
    return h;
}

constexpr uint64_t xorstr_key(uint64_t seed, size_t index)
{
    uint64_t k = seed ^ (index * 0x9E3779B97F4A7C15ULL);
    k ^= k >> 33;
    k *= 0xFF51AFD7ED558CCDULL;
    k ^= k >> 33;
    k *= 0xC4CEB9FE1A85EC53ULL;
    k ^= k >> 33;
    return k;
}

template<size_t N>
class XorString
{
public:
    constexpr XorString(const char (&str)[N], uint64_t seed)
        : seed_(seed)
    {
        for (size_t i = 0; i < N; ++i)
            data_[i] = str[i] ^ static_cast<char>(xorstr_key(seed, i) & 0xFF);
    }

    const char* decrypt() const
    {
        for (size_t i = 0; i < N; ++i)
            buf_[i] = data_[i] ^ static_cast<char>(xorstr_key(seed_, i) & 0xFF);
        return buf_;
    }

    void clear() const
    {
        volatile char* p = buf_;
        for (size_t i = 0; i < N; ++i)
            p[i] = 0;
    }

private:
    char data_[N]{};
    uint64_t seed_;
    mutable char buf_[N]{};
};

} // namespace detail

} // namespace mindless

#define XORSTR(s) ([]() -> const char* {                             \
    constexpr auto _xor = ::mindless::detail::XorString<sizeof(s)>(  \
        s, ::mindless::detail::xorstr_seed() ^ __LINE__);            \
    static const auto _inst = _xor;                                  \
    return _inst.decrypt();                                          \
}())
