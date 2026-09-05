#include "authclient/hwid.hpp"
#include "authclient/crypto.hpp"

#include <algorithm>
#include <mutex>
#include <string>

#ifdef _WIN32
#  define WIN32_LEAN_AND_MEAN
#  include <windows.h>
#  include <comdef.h>
#  include <wbemidl.h>
#else
#  include <fstream>
#  include <sstream>
#  include <cstdlib>
#  include <cstdio>
#  include <array>
#endif

namespace authclient {

// ---------------------------------------------------------------------------
// Platform-specific helpers
// ---------------------------------------------------------------------------

#ifdef _WIN32

/// Queries a single string property from WMI.
/// Returns empty string on any failure — never throws.
static std::string wmiQuerySingle(const wchar_t* wql_class,
                                  const wchar_t* property) {
    std::string result;

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool co_initialized = SUCCEEDED(hr) || hr == RPC_E_CHANGED_MODE;
    if (!co_initialized) return result;

    // Set default security levels
    hr = CoInitializeSecurity(nullptr, -1, nullptr, nullptr,
                              RPC_C_AUTHN_LEVEL_DEFAULT,
                              RPC_C_IMP_LEVEL_IMPERSONATE,
                              nullptr, EOAC_NONE, nullptr);
    // S_OK or RPC_E_TOO_LATE are both acceptable
    if (FAILED(hr) && hr != RPC_E_TOO_LATE) {
        CoUninitialize();
        return result;
    }

    IWbemLocator* locator = nullptr;
    hr = CoCreateInstance(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER,
                          IID_IWbemLocator, reinterpret_cast<void**>(&locator));
    if (FAILED(hr)) {
        CoUninitialize();
        return result;
    }

    IWbemServices* services = nullptr;
    hr = locator->ConnectServer(_bstr_t(L"ROOT\\CIMV2"), nullptr, nullptr,
                                nullptr, 0, nullptr, nullptr, &services);
    if (FAILED(hr)) {
        locator->Release();
        CoUninitialize();
        return result;
    }

    // Build query string, e.g. "SELECT ProcessorId FROM Win32_Processor"
    std::wstring query = L"SELECT ";
    query += property;
    query += L" FROM ";
    query += wql_class;

    IEnumWbemClassObject* enumerator = nullptr;
    hr = services->ExecQuery(_bstr_t(L"WQL"), _bstr_t(query.c_str()),
                             WBEM_FLAG_FORWARD_ONLY | WBEM_FLAG_RETURN_IMMEDIATELY,
                             nullptr, &enumerator);
    if (SUCCEEDED(hr) && enumerator) {
        IWbemClassObject* obj = nullptr;
        ULONG returned = 0;
        if (enumerator->Next(WBEM_INFINITE, 1, &obj, &returned) == S_OK && obj) {
            VARIANT vt;
            VariantInit(&vt);
            if (SUCCEEDED(obj->Get(property, 0, &vt, nullptr, nullptr))
                && vt.vt == VT_BSTR && vt.bstrVal) {
                // Convert BSTR (wide) to narrow string
                _bstr_t bstr(vt.bstrVal, false);
                result = static_cast<const char*>(bstr);
            }
            VariantClear(&vt);
            obj->Release();
        }
        enumerator->Release();
    }

    services->Release();
    locator->Release();
    CoUninitialize();

    // Trim whitespace
    result.erase(0, result.find_first_not_of(" \t\r\n"));
    result.erase(result.find_last_not_of(" \t\r\n") + 1);
    return result;
}

static std::string getPlatformCpuId() {
    return wmiQuerySingle(L"Win32_Processor", L"ProcessorId");
}

static std::string getPlatformDiskSerial() {
    return wmiQuerySingle(L"Win32_DiskDrive", L"SerialNumber");
}

#else  // Linux

/// Read the entire contents of a file into a string.  Returns empty on error.
static std::string readFileContents(const std::string& path) {
    std::ifstream f(path);
    if (!f.is_open()) return "";
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

/// Trim leading/trailing whitespace from a string in place and return it.
static std::string trim(std::string s) {
    s.erase(0, s.find_first_not_of(" \t\r\n"));
    s.erase(s.find_last_not_of(" \t\r\n") + 1);
    return s;
}

/// Run a shell command and capture its stdout.  Returns empty on failure.
static std::string runCommand(const std::string& cmd) {
    std::array<char, 256> buffer;
    std::string output;
    FILE* pipe = popen(cmd.c_str(), "r");
    if (!pipe) return "";
    while (fgets(buffer.data(), static_cast<int>(buffer.size()), pipe)) {
        output += buffer.data();
    }
    pclose(pipe);
    return trim(output);
}

static std::string getPlatformCpuId() {
    // Try the "Serial" line first (Raspberry Pi and some ARM boards).
    // If absent, concatenate vendor_id + model name + cpu MHz from the first
    // processor block.
    std::string cpuinfo = readFileContents("/proc/cpuinfo");
    if (cpuinfo.empty()) return "";

    std::istringstream stream(cpuinfo);
    std::string line;
    std::string vendor_id, model_name, cpu_mhz;

    while (std::getline(stream, line)) {
        auto colon = line.find(':');
        if (colon == std::string::npos) continue;

        std::string key = trim(line.substr(0, colon));
        std::string val = trim(line.substr(colon + 1));

        if (key == "Serial") return val;
        if (key == "vendor_id" && vendor_id.empty())  vendor_id  = val;
        if (key == "model name" && model_name.empty()) model_name = val;
        if (key == "cpu MHz" && cpu_mhz.empty())       cpu_mhz   = val;
    }

    // Concatenate and strip all whitespace
    std::string combined = vendor_id + model_name + cpu_mhz;
    combined.erase(std::remove_if(combined.begin(), combined.end(), ::isspace),
                   combined.end());
    return combined;
}

static std::string getPlatformDiskSerial() {
    // Try sysfs first
    std::string serial = trim(readFileContents("/sys/block/sda/device/serial"));
    if (!serial.empty()) return serial;

    // Fall back to udevadm
    std::string udev = runCommand("udevadm info --query=all --name=/dev/sda 2>/dev/null");
    std::istringstream stream(udev);
    std::string line;
    while (std::getline(stream, line)) {
        auto pos = line.find("ID_SERIAL_SHORT=");
        if (pos != std::string::npos) {
            return trim(line.substr(pos + 16));
        }
    }
    return "";
}

#endif  // _WIN32

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

std::string getHWID() {
    // Compute once and cache — hardware IDs don't change at runtime.
    static std::string cached_hwid;
    static std::once_flag flag;

    std::call_once(flag, []() {
        std::string cpu_id      = getPlatformCpuId();
        std::string disk_serial = getPlatformDiskSerial();

        std::string cpu_hash  = crypto::sha256Hex(cpu_id);
        std::string disk_hash = crypto::sha256Hex(disk_serial);

        // Concatenate the two 64-char hex strings and reverse the whole thing
        std::string combined = cpu_hash + disk_hash;
        std::reverse(combined.begin(), combined.end());
        cached_hwid = combined;
    });

    return cached_hwid;
}

}  // namespace authclient
