#include "authclient/crypto.hpp"
#include "authclient/types.hpp"

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <bcrypt.h>

#include <limits>
#include <string>
#include <vector>

namespace authclient {
namespace crypto {
namespace {

bool succeeded(NTSTATUS status) { return status >= 0; }

ULONG checkedSize(size_t size) {
    if (size > std::numeric_limits<ULONG>::max())
        throw AuthException("CRYPTO_ERROR", "crypto input is too large");
    return static_cast<ULONG>(size);
}

class Algorithm {
public:
    Algorithm(LPCWSTR id, ULONG flags = 0) {
        if (!succeeded(BCryptOpenAlgorithmProvider(&handle_, id, nullptr, flags)))
            throw AuthException("CRYPTO_ERROR", "failed to open crypto provider");
    }
    ~Algorithm() { if (handle_) BCryptCloseAlgorithmProvider(handle_, 0); }
    Algorithm(const Algorithm&) = delete;
    Algorithm& operator=(const Algorithm&) = delete;
    Algorithm(Algorithm&& other) noexcept : handle_(other.handle_) { other.handle_ = nullptr; }
    BCRYPT_ALG_HANDLE get() const { return handle_; }

private:
    BCRYPT_ALG_HANDLE handle_ = nullptr;
};

class Hash {
public:
    Hash(BCRYPT_ALG_HANDLE algorithm, std::vector<uint8_t>& object,
         const std::vector<uint8_t>* secret = nullptr) {
        PUCHAR secretData = secret && !secret->empty()
            ? const_cast<PUCHAR>(secret->data()) : nullptr;
        ULONG secretSize = secret ? checkedSize(secret->size()) : 0;
        if (!succeeded(BCryptCreateHash(algorithm, &handle_, object.data(),
                                       checkedSize(object.size()), secretData,
                                       secretSize, 0)))
            throw AuthException("CRYPTO_ERROR", "failed to create hash");
    }
    ~Hash() { if (handle_) BCryptDestroyHash(handle_); }
    Hash(const Hash&) = delete;
    Hash& operator=(const Hash&) = delete;
    BCRYPT_HASH_HANDLE get() const { return handle_; }

private:
    BCRYPT_HASH_HANDLE handle_ = nullptr;
};

class Key {
public:
    Key(BCRYPT_ALG_HANDLE algorithm, std::vector<uint8_t>& object,
        const std::vector<uint8_t>& secret) {
        if (!succeeded(BCryptGenerateSymmetricKey(
                algorithm, &handle_, object.data(), checkedSize(object.size()),
                const_cast<PUCHAR>(secret.data()), checkedSize(secret.size()), 0)))
            throw AuthException("CRYPTO_ERROR", "failed to create cipher key");
    }
    ~Key() { if (handle_) BCryptDestroyKey(handle_); }
    Key(const Key&) = delete;
    Key& operator=(const Key&) = delete;
    BCRYPT_KEY_HANDLE get() const { return handle_; }

private:
    BCRYPT_KEY_HANDLE handle_ = nullptr;
};

DWORD propertyDword(BCRYPT_HANDLE handle, LPCWSTR name) {
    DWORD value = 0;
    ULONG written = 0;
    if (!succeeded(BCryptGetProperty(handle, name,
                                     reinterpret_cast<PUCHAR>(&value),
                                     sizeof(value), &written, 0)) ||
        written != sizeof(value))
        throw AuthException("CRYPTO_ERROR", "failed to query crypto provider");
    return value;
}

std::vector<uint8_t> hashSha256(const uint8_t* data, size_t dataSize,
                                const std::vector<uint8_t>* secret = nullptr) {
    Algorithm algorithm(BCRYPT_SHA256_ALGORITHM,
                        secret ? BCRYPT_ALG_HANDLE_HMAC_FLAG : 0);
    std::vector<uint8_t> object(propertyDword(algorithm.get(), BCRYPT_OBJECT_LENGTH));
    std::vector<uint8_t> output(propertyDword(algorithm.get(), BCRYPT_HASH_LENGTH));
    Hash hash(algorithm.get(), object, secret);
    if (dataSize != 0 &&
        !succeeded(BCryptHashData(hash.get(), const_cast<PUCHAR>(data),
                                  checkedSize(dataSize), 0)))
        throw AuthException("CRYPTO_ERROR", "SHA-256 update failed");
    if (!succeeded(BCryptFinishHash(hash.get(), output.data(),
                                    checkedSize(output.size()), 0)))
        throw AuthException("CRYPTO_ERROR", "SHA-256 digest failed");
    return output;
}

std::vector<uint8_t> hmacSha256(const std::vector<uint8_t>& key,
                                const uint8_t* data, size_t dataSize) {
    return hashSha256(data, dataSize, &key);
}

Algorithm aesGcmAlgorithm() {
    Algorithm algorithm(BCRYPT_AES_ALGORITHM);
    auto mode = reinterpret_cast<PUCHAR>(const_cast<wchar_t*>(BCRYPT_CHAIN_MODE_GCM));
    if (!succeeded(BCryptSetProperty(algorithm.get(), BCRYPT_CHAINING_MODE,
                                     mode, sizeof(BCRYPT_CHAIN_MODE_GCM), 0)))
        throw AuthException("CRYPTO_ERROR", "failed to enable AES-GCM");
    return algorithm;
}

}  // namespace

std::string toHex(const std::vector<uint8_t>& bytes) {
    static constexpr char hexChars[] = "0123456789abcdef";
    std::string result;
    result.reserve(bytes.size() * 2);
    for (uint8_t byte : bytes) {
        result.push_back(hexChars[byte >> 4]);
        result.push_back(hexChars[byte & 0x0f]);
    }
    return result;
}

std::vector<uint8_t> fromHex(const std::string& hex) {
    if (hex.size() % 2 != 0)
        throw AuthException("INVALID_HEX", "hex string has odd length");
    auto nibble = [](char value) -> uint8_t {
        if (value >= '0' && value <= '9') return static_cast<uint8_t>(value - '0');
        if (value >= 'a' && value <= 'f') return static_cast<uint8_t>(value - 'a' + 10);
        if (value >= 'A' && value <= 'F') return static_cast<uint8_t>(value - 'A' + 10);
        throw AuthException("INVALID_HEX", "non-hex character in string");
    };
    std::vector<uint8_t> bytes;
    bytes.reserve(hex.size() / 2);
    for (size_t index = 0; index < hex.size(); index += 2)
        bytes.push_back(static_cast<uint8_t>((nibble(hex[index]) << 4) |
                                             nibble(hex[index + 1])));
    return bytes;
}

std::string sha256Hex(const std::string& input) {
    return toHex(hashSha256(reinterpret_cast<const uint8_t*>(input.data()),
                            input.size()));
}

std::vector<uint8_t> randomBytes(size_t length) {
    std::vector<uint8_t> bytes(length);
    if (length != 0 &&
        !succeeded(BCryptGenRandom(nullptr, bytes.data(), checkedSize(length),
                                   BCRYPT_USE_SYSTEM_PREFERRED_RNG)))
        throw AuthException("CRYPTO_ERROR", "CSPRNG failed");
    return bytes;
}

std::vector<uint8_t> hkdfSha256(const std::vector<uint8_t>& ikm,
                                const std::vector<uint8_t>& salt,
                                const std::vector<uint8_t>& info,
                                size_t length) {
    constexpr size_t hashLength = 32;
    std::vector<uint8_t> effectiveSalt = salt;
    if (effectiveSalt.empty()) effectiveSalt.resize(hashLength, 0);
    auto prk = hmacSha256(effectiveSalt, ikm.data(), ikm.size());
    size_t blockCount = (length + hashLength - 1) / hashLength;
    if (blockCount > 255)
        throw AuthException("CRYPTO_ERROR", "HKDF output length too large");
    std::vector<uint8_t> output;
    output.reserve(blockCount * hashLength);
    std::vector<uint8_t> previous;
    for (size_t index = 1; index <= blockCount; ++index) {
        std::vector<uint8_t> input;
        input.reserve(previous.size() + info.size() + 1);
        input.insert(input.end(), previous.begin(), previous.end());
        input.insert(input.end(), info.begin(), info.end());
        input.push_back(static_cast<uint8_t>(index));
        previous = hmacSha256(prk, input.data(), input.size());
        output.insert(output.end(), previous.begin(), previous.end());
    }
    output.resize(length);
    return output;
}

std::vector<uint8_t> aesGcmDecrypt(const std::vector<uint8_t>& data,
                                   const std::vector<uint8_t>& keyBytes) {
    constexpr size_t nonceSize = 12;
    constexpr size_t tagSize = 16;
    if (data.size() < nonceSize + tagSize)
        throw AuthException("DECRYPTION_FAILED", "data too short to contain nonce and tag");
    if (keyBytes.size() != 32)
        throw AuthException("DECRYPTION_FAILED", "AES-256 key must be exactly 32 bytes");
    Algorithm algorithm = aesGcmAlgorithm();
    std::vector<uint8_t> keyObject(propertyDword(algorithm.get(), BCRYPT_OBJECT_LENGTH));
    Key key(algorithm.get(), keyObject, keyBytes);
    size_t ciphertextSize = data.size() - nonceSize - tagSize;
    auto* ciphertext = data.data() + nonceSize;
    std::vector<uint8_t> tag(data.end() - tagSize, data.end());
    std::vector<uint8_t> plaintext(ciphertextSize);
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO authInfo;
    BCRYPT_INIT_AUTH_MODE_INFO(authInfo);
    authInfo.pbNonce = const_cast<PUCHAR>(data.data());
    authInfo.cbNonce = checkedSize(nonceSize);
    authInfo.pbTag = tag.data();
    authInfo.cbTag = checkedSize(tagSize);
    ULONG written = 0;
    NTSTATUS status = BCryptDecrypt(
        key.get(), const_cast<PUCHAR>(ciphertext), checkedSize(ciphertextSize),
        &authInfo, nullptr, 0, plaintext.data(), checkedSize(plaintext.size()),
        &written, 0);
    if (!succeeded(status))
        throw AuthException("DECRYPTION_FAILED", "AES-GCM tag verification failed");
    plaintext.resize(written);
    return plaintext;
}

std::vector<uint8_t> aesGcmEncrypt(const std::string& plaintext,
                                   const std::vector<uint8_t>& keyBytes) {
    constexpr size_t nonceSize = 12;
    constexpr size_t tagSize = 16;
    if (keyBytes.size() != 32)
        throw AuthException("ENCRYPTION_FAILED", "AES-256 key must be exactly 32 bytes");
    Algorithm algorithm = aesGcmAlgorithm();
    std::vector<uint8_t> keyObject(propertyDword(algorithm.get(), BCRYPT_OBJECT_LENGTH));
    Key key(algorithm.get(), keyObject, keyBytes);
    auto nonce = randomBytes(nonceSize);
    std::vector<uint8_t> ciphertext(plaintext.size());
    std::vector<uint8_t> tag(tagSize);
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO authInfo;
    BCRYPT_INIT_AUTH_MODE_INFO(authInfo);
    authInfo.pbNonce = nonce.data();
    authInfo.cbNonce = checkedSize(nonceSize);
    authInfo.pbTag = tag.data();
    authInfo.cbTag = checkedSize(tagSize);
    ULONG written = 0;
    NTSTATUS status = BCryptEncrypt(
        key.get(), reinterpret_cast<PUCHAR>(const_cast<char*>(plaintext.data())),
        checkedSize(plaintext.size()), &authInfo, nullptr, 0,
        ciphertext.data(), checkedSize(ciphertext.size()), &written, 0);
    if (!succeeded(status))
        throw AuthException("ENCRYPTION_FAILED", "AES-GCM encryption failed");
    std::vector<uint8_t> output;
    output.reserve(nonce.size() + written + tag.size());
    output.insert(output.end(), nonce.begin(), nonce.end());
    output.insert(output.end(), ciphertext.begin(), ciphertext.begin() + written);
    output.insert(output.end(), tag.begin(), tag.end());
    return output;
}

}  // namespace crypto
}  // namespace authclient
