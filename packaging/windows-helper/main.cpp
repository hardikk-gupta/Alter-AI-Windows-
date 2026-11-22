#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincred.h>
#include <cstdio>
#include <cwchar>
#include <string>

static int watchShortcut() {
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

int wmain(int argc, wchar_t** argv) {
    if (argc == 2 && wcscmp(argv[1], L"shortcut") == 0) return watchShortcut();
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
