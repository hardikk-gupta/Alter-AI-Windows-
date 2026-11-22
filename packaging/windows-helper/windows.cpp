#include <windows.h>
#include <ole2.h>
#include <oleacc.h>
#include <UIAutomation.h>
#include <wrl/client.h>
#include <cstdio>
#include <string>
#include <iostream>

using Microsoft::WRL::ComPtr;

static std::string titleOf(HWND window) {
    wchar_t title[512]{};
    int length = GetWindowTextW(window, title, 512);
    if (length <= 0) return {};
    for (int i = 0; i < length; ++i) if (title[i] < 32) title[i] = L' ';
    int count = WideCharToMultiByte(CP_UTF8, 0, title, length, nullptr, 0, nullptr, nullptr);
    std::string result(count, ' ');
    WideCharToMultiByte(CP_UTF8, 0, title, length, result.data(), count, nullptr, nullptr);
    return result;
}

static bool selectable(HWND window) {
    return IsWindowVisible(window) && GetAncestor(window, GA_ROOT) == window &&
        !GetWindow(window, GW_OWNER) && !(GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) &&
        window != GetShellWindow();
}

static BOOL CALLBACK listWindow(HWND window, LPARAM excludePid) {
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    if (pid != static_cast<DWORD>(excludePid) && selectable(window)) {
        auto title = titleOf(window);
        if (!title.empty()) std::printf("%llu\t%lu\t%s\n", (unsigned long long)(UINT_PTR)window, pid, title.c_str());
    }
    return TRUE;
}

int listWindows(DWORD excludePid) {
    return EnumWindows(listWindow, excludePid) ? 0 : 1;
}

int setWindowState(HWND window, DWORD pid, bool maximize) {
    DWORD actual = 0;
    std::string approved;
    std::getline(std::cin, approved);
    if (!GetWindowThreadProcessId(window, &actual) || actual != pid || !selectable(window) ||
        approved.empty() || approved != titleOf(window)) return 2;

    if (FAILED(CoInitializeEx(nullptr, COINIT_MULTITHREADED))) return 1;
    int result = 1;
    {
        ComPtr<IUIAutomation> automation;
        ComPtr<IUIAutomationElement> element;
        ComPtr<IUIAutomationWindowPattern> pattern;
        auto state = maximize ? WindowVisualState_Maximized : WindowVisualState_Normal;
        if (SUCCEEDED(CoCreateInstance(CLSID_CUIAutomation, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&automation))) &&
            SUCCEEDED(automation->ElementFromHandle(window, &element)) &&
            SUCCEEDED(element->GetCurrentPatternAs(UIA_WindowPatternId, IID_PPV_ARGS(&pattern))) && pattern &&
            SUCCEEDED(pattern->SetWindowVisualState(state))) {
            for (int i = 0; i < 25; ++i) {
                WindowVisualState current;
                if (SUCCEEDED(pattern->get_CurrentWindowVisualState(&current)) && current == state) { result = 0; break; }
                Sleep(40);
            }
        }
    }
    CoUninitialize();
    return result;
}
