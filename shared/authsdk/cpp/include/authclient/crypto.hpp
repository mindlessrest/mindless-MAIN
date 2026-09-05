#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace authclient {
namespace crypto {

/// Returns the lowercase hex-encoded SHA-256 digest of `input`.
std::string sha256Hex(const std::string& input);

/// HKDF-SHA256 key derivation (RFC 5869).
///
/// @param ikm    Input key material (e.g. raw session token bytes).
/// @param salt   Optional salt (e.g. file_id as UTF-8 bytes).
/// @param info   Context/application info (e.g. "file-download").
/// @param length Desired output length in bytes (typically 32).
/// @return       Derived key material of the requested length.
std::vector<uint8_t> hkdfSha256(const std::vector<uint8_t>& ikm,
                                const std::vector<uint8_t>& salt,
                                const std::vector<uint8_t>& info,
                                size_t length);

/// Decrypts an AES-256-GCM payload.
///
/// The first 12 bytes of `data` are the GCM nonce; the remainder is
/// ciphertext with the 16-byte authentication tag appended.
///
/// @throws AuthException with code "DECRYPTION_FAILED" if the GCM tag
///         does not verify.
std::vector<uint8_t> aesGcmDecrypt(const std::vector<uint8_t>& data,
                                   const std::vector<uint8_t>& key);

/// Encrypts `plaintext` with AES-256-GCM.
///
/// Generates a random 12-byte nonce and prepends it to the output.
/// Wire format: [12-byte nonce][ciphertext + 16-byte GCM tag].
std::vector<uint8_t> aesGcmEncrypt(const std::string& plaintext,
                                   const std::vector<uint8_t>& key);

/// Generates `length` cryptographically random bytes.
std::vector<uint8_t> randomBytes(size_t length);

/// Hex-encodes a byte vector to a lowercase string.
std::string toHex(const std::vector<uint8_t>& bytes);

/// Decodes a hex string into raw bytes.
std::vector<uint8_t> fromHex(const std::string& hex);

}  // namespace crypto
}  // namespace authclient
