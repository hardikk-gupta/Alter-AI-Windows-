#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincred.h>
#include <psapi.h>
#include <cstdio>
#include <cwchar>
#include <string>

static DWORD WINAPI waitForParent(void* parent) {
    WaitForSingleObject(static_cast<HANDLE>(parent), INFINITE);
    ExitProcess(0);
}

static int watchShortcut(DWORD parentId) {
    HANDLE parent = OpenProcess(SYNCHRONIZE, FALSE, parentId);
    if (!parent) return 1;
    HANDLE watcher = CreateThread(nullptr, 0, waitForParent, parent, 0, nullptr);
    if (!watcher) return 1;
    CloseHandle(watcher);
    if (!RegisterHotKey(nullptr, 1, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, VK_SPACE)) return 2;
    puts("ready"); fflush(stdout);
    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        if (message.message != WM_HOTKEY || message.wParam != 1) continue;
        puts("down"); fflush(stdout);
        while ((GetAsyncKeyState(VK_SPACE) & 0x8000) && (GetAsyncKeyState(VK_CONTROL) & 0x8000)
                && (GetAsyncKeyState(VK_MENU) & 0x8000)) Sleep(20);
        puts("up"); fflush(stdout);
    }
    UnregisterHotKey(nullptr, 1);
    return 0;
}

int listWindows(DWORD excludePid);
int setWindowState(HWND window, DWORD pid, bool maximize);

int wmain(int argc, wchar_t** argv) {
    if (argc == 3 && wcscmp(argv[1], L"peak-memory") == 0) {
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, FALSE, wcstoul(argv[2], nullptr, 10));
        if (!process) return 1;
        PROCESS_MEMORY_COUNTERS usage{};
        bool ok = GetProcessMemoryInfo(process, &usage, sizeof(usage)) != 0;
        CloseHandle(process);
        if (ok) std::printf("%llu\n", (unsigned long long)usage.PeakWorkingSetSize);
        return ok ? 0 : 1;
    }
    if (argc == 3 && wcscmp(argv[1], L"windows") == 0) return listWindows(wcstoul(argv[2], nullptr, 10));
    if (argc == 4 && (wcscmp(argv[1], L"maximize") == 0 || wcscmp(argv[1], L"restore") == 0))
        return setWindowState(reinterpret_cast<HWND>(wcstoull(argv[2], nullptr, 10)), wcstoul(argv[3], nullptr, 10),
            wcscmp(argv[1], L"maximize") == 0);
    if (argc == 3 && wcscmp(argv[1], L"shortcut") == 0) return watchShortcut(wcstoul(argv[2], nullptr, 10));
    if (argc != 3) return 1;
    std::wstring id(argv[2]);
    if (id.empty() || id.size() > 19 || id.find_first_not_of(L"0123456789") != std::wstring::npos) return 1;
    std::wstring target = L"Alter/OpenAI/Project/" + id;
    if (wcscmp(argv[1], L"forget") == 0)
        return CredDeleteW(target.c_str(), CRED_TYPE_GENERIC, 0) || GetLastError() == ERROR_NOT_FOUND ? 0 : 1;
    if (wcscmp(argv[1], L"load") == 0) {
        PCREDENTIALW cred = nullptr;
        if (!CredReadW(target.c_str(), CRED_TYPE_GENERIC, 0, &cred)) return 1;
        size_t count = fwrite(cred->CredentialBlob, 1, cred->CredentialBlobSize, stdout);
        bool ok = count == cred->CredentialBlobSize;
        CredFree(cred);
        return ok ? 0 : 1;
    }
    if (wcscmp(argv[1], L"save") != 0) return 1;
    BYTE buffer[CRED_MAX_CREDENTIAL_BLOB_SIZE + 1];
    size_t count = fread(buffer, 1, sizeof(buffer), stdin);
    if (!count || count >= sizeof(buffer)) return 1;
    CREDENTIALW cred{};
    cred.Type = CRED_TYPE_GENERIC;
    cred.TargetName = target.data();
    cred.CredentialBlob = buffer;
    cred.CredentialBlobSize = static_cast<DWORD>(count);
    cred.Persist = CRED_PERSIST_LOCAL_MACHINE;
    bool ok = CredWriteW(&cred, 0);
    SecureZeroMemory(buffer, sizeof(buffer));
    return ok ? 0 : 1;
}
