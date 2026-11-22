#include <windows.h>

static LRESULT CALLBACK handleMessage(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcW(window, message, w, l);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR title, int) {
    WNDCLASSW type{};
    type.lpfnWndProc = handleMessage;
    type.hInstance = instance;
    type.lpszClassName = L"AlterControlTest";
    if (!RegisterClassW(&type)) return 1;
    HWND window = CreateWindowExW(0, type.lpszClassName, title, WS_OVERLAPPEDWINDOW | WS_VISIBLE,
        CW_USEDEFAULT, CW_USEDEFAULT, 500, 300, nullptr, nullptr, instance, nullptr);
    if (!window) return 2;
    ShowWindow(window, SW_SHOWNORMAL);
    UpdateWindow(window);
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return 0;
}
