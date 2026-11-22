// Older per-user jpackage MSIs recorded the whole workspace as their uninstall
// cleanup directory. Limit that legacy cleanup to its bundled Java runtime.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <msi.h>
#include <msiquery.h>
#include <shlobj.h>
#include <algorithm>
#include <string>
#include <vector>

static std::wstring pathKey(std::wstring path) {
    std::replace(path.begin(), path.end(), L'/', L'\\');
    while (!path.empty() && path.back() == L'\\') path.pop_back();
    return path;
}

static bool samePath(const std::wstring& left, const std::wstring& right) {
    return _wcsicmp(pathKey(left).c_str(), pathKey(right).c_str()) == 0;
}

static std::wstring productProperty(const wchar_t* product, const wchar_t* property) {
    DWORD length = 0;
    UINT result = MsiGetProductInfoW(product, property, nullptr, &length);
    if (result != ERROR_SUCCESS && result != ERROR_MORE_DATA) return {};
    std::vector<wchar_t> value(++length);
    if (MsiGetProductInfoW(product, property, value.data(), &length) != ERROR_SUCCESS) return {};
    return value.data();
}

static bool protectView(const std::wstring& subkey, REGSAM view, const std::wstring& workspace, bool& found) {
    HKEY key{};
    LSTATUS status = RegOpenKeyExW(HKEY_CURRENT_USER, subkey.c_str(), 0,
        KEY_QUERY_VALUE | KEY_SET_VALUE | view, &key);
    if (status == ERROR_FILE_NOT_FOUND) return true;
    if (status != ERROR_SUCCESS) return false;
    bool ok = true;
    for (DWORD index = 0; ; ++index) {
        wchar_t name[256]{};
        DWORD nameSize = ARRAYSIZE(name), bytes = 0, type = 0;
        status = RegEnumValueW(key, index, name, &nameSize, nullptr, &type, nullptr, &bytes);
        if (status == ERROR_NO_MORE_ITEMS) break;
        if (status != ERROR_SUCCESS) { ok = false; break; }
        if (wcsncmp(name, L"RM_RF", 5) != 0 || type != REG_SZ) continue;
        std::vector<wchar_t> value(bytes / sizeof(wchar_t) + 1);
        if (RegQueryValueExW(key, name, nullptr, nullptr,
            reinterpret_cast<BYTE*>(value.data()), &bytes) != ERROR_SUCCESS) { ok = false; break; }
        const auto runtime = workspace + L"\\runtime";
        if (samePath(value.data(), runtime)) { found = true; continue; }
        if (!samePath(value.data(), workspace)) { ok = false; break; }
        if (RegSetValueExW(key, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(runtime.c_str()),
            static_cast<DWORD>((runtime.size() + 1) * sizeof(wchar_t))) != ERROR_SUCCESS) { ok = false; break; }
        found = true;
    }
    RegCloseKey(key);
    return ok;
}

extern "C" __declspec(dllexport) UINT __stdcall ProtectLegacyWorkspace(MSIHANDLE session) {
    wchar_t local[MAX_PATH]{};
    if (SHGetFolderPathW(nullptr, CSIDL_LOCAL_APPDATA, nullptr, SHGFP_TYPE_CURRENT, local) != S_OK)
        return ERROR_INSTALL_FAILURE;
    const std::wstring workspace = std::wstring(local) + L"\\Alter";
    wchar_t product[39]{};
    for (DWORD index = 0; ; ++index) {
        UINT result = MsiEnumRelatedProductsW(L"{0D5D9A26-99A2-5541-BF39-A416D2B6947A}", 0, index, product);
        if (result == ERROR_NO_MORE_ITEMS) return ERROR_SUCCESS;
        if (result != ERROR_SUCCESS) return ERROR_INSTALL_FAILURE;
        const auto location = productProperty(product, INSTALLPROPERTY_INSTALLLOCATION);
        if (location.empty()) return ERROR_INSTALL_FAILURE;
        if (!samePath(location, workspace)) continue;
        const auto vendor = productProperty(product, INSTALLPROPERTY_PUBLISHER);
        const auto version = productProperty(product, INSTALLPROPERTY_VERSIONSTRING);
        if (vendor.empty() || version.empty() || vendor.find(L'\\') != vendor.npos ||
            version.find(L'\\') != version.npos) return ERROR_INSTALL_FAILURE;
        const auto key = L"Software\\" + vendor + L"\\Alter\\" + version;
        bool found = false;
        if (!protectView(key, KEY_WOW64_64KEY, workspace, found) ||
            !protectView(key, KEY_WOW64_32KEY, workspace, found) || !found) {
            MSIHANDLE message = MsiCreateRecord(0);
            MsiRecordSetStringW(message, 0, L"Alter could not protect the previous workspace. Upgrade stopped before removal.");
            MsiProcessMessage(session, INSTALLMESSAGE_ERROR, message);
            MsiCloseHandle(message);
            return ERROR_INSTALL_FAILURE;
        }
    }
}
