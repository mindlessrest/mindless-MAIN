#pragma once
#include <windows.h>
#include <wincrypt.h>
#include <shlobj.h>
#include <string>
#include <vector>

namespace mindless
{

inline std::wstring get_auth_file_path()
{
    wchar_t appdata[MAX_PATH] = {};
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, appdata)))
    {
        std::wstring dir = std::wstring(appdata) + L"\\Mindless";
        CreateDirectoryW(dir.c_str(), nullptr);
        return dir + L"\\auth.dat";
    }
    return L"auth.dat";
}

inline bool save_credentials(const std::string& user, const std::string& pass, bool remember)
{
    std::wstring path = get_auth_file_path();
    if (!remember || user.empty())
    {
        DeleteFileW(path.c_str());
        return true;
    }

    std::string payload = user + "\n" + pass + "\n" + (remember ? "1" : "0");

    DATA_BLOB inBlob = {};
    inBlob.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(payload.data()));
    inBlob.cbData = static_cast<DWORD>(payload.size());

    DATA_BLOB outBlob = {};
    if (!CryptProtectData(&inBlob, L"MindlessAuth", nullptr, nullptr, nullptr, 0, &outBlob))
        return false;

    HANDLE hFile = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE)
    {
        LocalFree(outBlob.pbData);
        return false;
    }

    DWORD written = 0;
    WriteFile(hFile, outBlob.pbData, outBlob.cbData, &written, nullptr);
    CloseHandle(hFile);
    LocalFree(outBlob.pbData);
    return true;
}

inline bool load_credentials(std::string& user, std::string& pass, bool& remember)
{
    std::wstring path = get_auth_file_path();
    HANDLE hFile = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return false;

    DWORD size = GetFileSize(hFile, nullptr);
    if (size == 0 || size == INVALID_FILE_SIZE)
    {
        CloseHandle(hFile);
        return false;
    }

    std::vector<BYTE> cipher(size);
    DWORD read = 0;
    if (!ReadFile(hFile, cipher.data(), size, &read, nullptr) || read != size)
    {
        CloseHandle(hFile);
        return false;
    }
    CloseHandle(hFile);

    DATA_BLOB inBlob = {};
    inBlob.pbData = cipher.data();
    inBlob.cbData = size;

    DATA_BLOB outBlob = {};
    if (!CryptUnprotectData(&inBlob, nullptr, nullptr, nullptr, nullptr, 0, &outBlob))
        return false;

    std::string payload(reinterpret_cast<char*>(outBlob.pbData), outBlob.cbData);
    LocalFree(outBlob.pbData);

    size_t p1 = payload.find('\n');
    if (p1 == std::string::npos) return false;
    size_t p2 = payload.find('\n', p1 + 1);

    user = payload.substr(0, p1);
    if (p2 == std::string::npos)
    {
        pass = payload.substr(p1 + 1);
        remember = true;
    }
    else
    {
        pass = payload.substr(p1 + 1, p2 - (p1 + 1));
        remember = payload.substr(p2 + 1) == "1";
    }

    return true;
}

} // namespace mindless
