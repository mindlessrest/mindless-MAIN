#include "authclient/crypto.hpp"
#include "authclient/types.hpp"

#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <algorithm>
#include <cstring>
#include <sstream>
#include <stdexcept>

namespace authclient {
namespace crypto {

// ---------------------------------------------------------------------------
// Hex encoding / decoding
// ---------------------------------------------------------------------------

std::string toHex(const std::vector<uint8_t>& bytes) {
    static const char hex_chars[] = "0123456789abcdef";
    std::string result;
    result.reserve(bytes.size() * 2);
    for (uint8_t b : bytes) {
        result.push_back(hex_chars[b >> 4]);
        result.push_back(hex_chars[b & 0x0F]);
    }
    return result;
}

std::vector<uint8_t> fromHex(const std::string& hex) {
    if (hex.size() % 2 != 0) {
        throw AuthException("INVALID_HEX", "hex string has odd length");
    }
    std::vector<uint8_t> bytes;
    bytes.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        auto hi = hex[i];
        auto lo = hex[i + 1];
        auto nibble = [](char c) -> uint8_t {
            if (c >= '0' && c <= '9') return static_cast<uint8_t>(c - '0');
            if (c >= 'a' && c <= 'f') return static_cast<uint8_t>(c - 'a' + 10);
            if (c >= 'A' && c <= 'F') return static_cast<uint8_t>(c - 'A' + 10);
            throw AuthException("INVALID_HEX", "non-hex character in string");
        };
        bytes.push_back(static_cast<uint8_t>((nibble(hi) << 4) | nibble(lo)));
    }
    return bytes;
}

// ---------------------------------------------------------------------------
// SHA-256
// ---------------------------------------------------------------------------

std::string sha256Hex(const std::string& input) {
    unsigned char hash[EVP_MAX_MD_SIZE];
    unsigned int hash_len = 0;

    EVP_MD_CTX* ctx = EVP_MD_CTX_new();
    if (!ctx) {
        throw AuthException("CRYPTO_ERROR", "failed to create EVP_MD_CTX");
    }

    bool ok = EVP_DigestInit_ex(ctx, EVP_sha256(), nullptr) == 1
           && EVP_DigestUpdate(ctx, input.data(), input.size()) == 1
           && EVP_DigestFinal_ex(ctx, hash, &hash_len) == 1;
    EVP_MD_CTX_free(ctx);

    if (!ok) {
        throw AuthException("CRYPTO_ERROR", "SHA-256 digest failed");
    }

    std::vector<uint8_t> vec(hash, hash + hash_len);
    return toHex(vec);
}

// ---------------------------------------------------------------------------
// Cryptographically secure random bytes
// ---------------------------------------------------------------------------

std::vector<uint8_t> randomBytes(size_t length) {
    std::vector<uint8_t> buf(length);
    if (RAND_bytes(buf.data(), static_cast<int>(length)) != 1) {
        throw AuthException("CRYPTO_ERROR", "CSPRNG failed");
    }
    return buf;
}

// ---------------------------------------------------------------------------
// HKDF-SHA256 (RFC 5869) — manual implementation using HMAC-SHA256
//
// HKDF has two phases:
//   1. Extract — compress the input key material (IKM) into a fixed-length
//      pseudorandom key (PRK) using the salt.
//      PRK = HMAC-SHA256(salt, IKM)
//
//   2. Expand — produce the desired amount of output key material from the
//      PRK by iteratively computing HMAC blocks.
//      T(0) = empty
//      T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)   [i is a single byte]
//      Output = first `length` bytes of T(1) || T(2) || ...
// ---------------------------------------------------------------------------

static std::vector<uint8_t> hmacSha256(const std::vector<uint8_t>& key,
                                       const uint8_t* data, size_t data_len) {
    unsigned char result[EVP_MAX_MD_SIZE];
    unsigned int result_len = 0;

    HMAC(EVP_sha256(),
         key.data(), static_cast<int>(key.size()),
         data, data_len,
         result, &result_len);

    return std::vector<uint8_t>(result, result + result_len);
}

std::vector<uint8_t> hkdfSha256(const std::vector<uint8_t>& ikm,
                                const std::vector<uint8_t>& salt,
                                const std::vector<uint8_t>& info,
                                size_t length) {
    const size_t hash_len = 32;  // SHA-256 output size

    // --- Step 1: Extract ---
    // PRK = HMAC-SHA256(salt, IKM)
    // If salt is empty, RFC 5869 says to use a zero-filled string of hash_len.
    std::vector<uint8_t> effective_salt = salt;
    if (effective_salt.empty()) {
        effective_salt.resize(hash_len, 0);
    }
    std::vector<uint8_t> prk = hmacSha256(effective_salt, ikm.data(), ikm.size());

    // --- Step 2: Expand ---
    // Iteratively produce hash_len-sized blocks until we have enough output.
    // Each block: T(i) = HMAC-SHA256(PRK, T(i-1) || info || counter_byte)
    size_t blocks_needed = (length + hash_len - 1) / hash_len;
    if (blocks_needed > 255) {
        throw AuthException("CRYPTO_ERROR", "HKDF output length too large");
    }

    std::vector<uint8_t> output;
    output.reserve(blocks_needed * hash_len);
    std::vector<uint8_t> previous_block;  // T(i-1), empty for first iteration

    for (size_t i = 1; i <= blocks_needed; ++i) {
        // Build the HMAC input: T(i-1) || info || counter_byte
        std::vector<uint8_t> hmac_input;
        hmac_input.reserve(previous_block.size() + info.size() + 1);
        hmac_input.insert(hmac_input.end(), previous_block.begin(), previous_block.end());
        hmac_input.insert(hmac_input.end(), info.begin(), info.end());
        hmac_input.push_back(static_cast<uint8_t>(i));

        previous_block = hmacSha256(prk, hmac_input.data(), hmac_input.size());
        output.insert(output.end(), previous_block.begin(), previous_block.end());
    }

    output.resize(length);
    return output;
}

// ---------------------------------------------------------------------------
// AES-256-GCM decryption
//
// Input layout: [12-byte nonce][ciphertext with 16-byte GCM tag appended]
// ---------------------------------------------------------------------------

std::vector<uint8_t> aesGcmDecrypt(const std::vector<uint8_t>& data,
                                   const std::vector<uint8_t>& key) {
    const size_t NONCE_SIZE = 12;
    const size_t TAG_SIZE   = 16;

    if (data.size() < NONCE_SIZE + TAG_SIZE) {
        throw AuthException("DECRYPTION_FAILED",
                            "data too short to contain nonce + tag");
    }
    if (key.size() != 32) {
        throw AuthException("DECRYPTION_FAILED",
                            "AES-256 key must be exactly 32 bytes");
    }

    const uint8_t* nonce      = data.data();
    const uint8_t* ciphertext = data.data() + NONCE_SIZE;
    size_t ct_len = data.size() - NONCE_SIZE;

    // OpenSSL expects the tag to be separated from the ciphertext.
    // The tag is the last 16 bytes of the ciphertext blob.
    size_t actual_ct_len = ct_len - TAG_SIZE;
    const uint8_t* tag   = ciphertext + actual_ct_len;

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw AuthException("CRYPTO_ERROR", "failed to create cipher context");
    }

    std::vector<uint8_t> plaintext(actual_ct_len);
    int len = 0;
    int plaintext_len = 0;
    bool ok = true;

    ok = ok && EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1;
    ok = ok && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) == 1;
    ok = ok && EVP_DecryptInit_ex(ctx, nullptr, nullptr, key.data(), nonce) == 1;
    ok = ok && EVP_DecryptUpdate(ctx, plaintext.data(), &len, ciphertext, static_cast<int>(actual_ct_len)) == 1;
    plaintext_len = len;

    // Set the expected GCM tag before calling DecryptFinal.
    // If the tag doesn't match, DecryptFinal returns 0 (failure).
    ok = ok && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_SIZE,
                                   const_cast<uint8_t*>(tag)) == 1;

    int final_ret = EVP_DecryptFinal_ex(ctx, plaintext.data() + plaintext_len, &len);
    EVP_CIPHER_CTX_free(ctx);

    if (!ok || final_ret <= 0) {
        throw AuthException("DECRYPTION_FAILED",
                            "AES-GCM tag verification failed — data may be "
                            "corrupted or the wrong key was used");
    }

    plaintext_len += len;
    plaintext.resize(static_cast<size_t>(plaintext_len));
    return plaintext;
}

// ---------------------------------------------------------------------------
// AES-256-GCM encryption
//
// Output layout: [12-byte random nonce][ciphertext][16-byte GCM tag]
// ---------------------------------------------------------------------------

std::vector<uint8_t> aesGcmEncrypt(const std::string& plaintext,
                                   const std::vector<uint8_t>& key) {
    const size_t NONCE_SIZE = 12;
    const size_t TAG_SIZE   = 16;

    if (key.size() != 32) {
        throw AuthException("ENCRYPTION_FAILED",
                            "AES-256 key must be exactly 32 bytes");
    }

    auto nonce = randomBytes(NONCE_SIZE);

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw AuthException("CRYPTO_ERROR", "failed to create cipher context");
    }

    // Output buffer: nonce + ciphertext (same length as plaintext) + tag
    std::vector<uint8_t> output;
    output.reserve(NONCE_SIZE + plaintext.size() + TAG_SIZE);
    output.insert(output.end(), nonce.begin(), nonce.end());

    std::vector<uint8_t> ciphertext(plaintext.size() + TAG_SIZE);
    int len = 0;
    int ct_len = 0;
    bool ok = true;

    ok = ok && EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1;
    ok = ok && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, nullptr) == 1;
    ok = ok && EVP_EncryptInit_ex(ctx, nullptr, nullptr, key.data(), nonce.data()) == 1;
    ok = ok && EVP_EncryptUpdate(ctx, ciphertext.data(), &len,
                                 reinterpret_cast<const uint8_t*>(plaintext.data()),
                                 static_cast<int>(plaintext.size())) == 1;
    ct_len = len;
    ok = ok && EVP_EncryptFinal_ex(ctx, ciphertext.data() + ct_len, &len) == 1;
    ct_len += len;

    // Retrieve the GCM authentication tag
    unsigned char tag[TAG_SIZE];
    ok = ok && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_SIZE, tag) == 1;
    EVP_CIPHER_CTX_free(ctx);

    if (!ok) {
        throw AuthException("ENCRYPTION_FAILED", "AES-GCM encryption failed");
    }

    output.insert(output.end(), ciphertext.begin(), ciphertext.begin() + ct_len);
    output.insert(output.end(), tag, tag + TAG_SIZE);
    return output;
}

}  // namespace crypto
}  // namespace authclient
