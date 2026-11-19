/*
 * BridgeTap standalone injector — one exe, no external files, no JVM of its own.
 *
 * Everything needed to put BridgeTap into a running Minecraft 1.8.9 client is embedded as a
 * resource and materialised at injection time:
 *
 *   RCDATA 1  agent.jar     the JVMTI agent (Bootstrap / Bridge / Tracker / ASM)
 *   RCDATA 2  hud.jar       the GL overlay, loaded into the client's own classloader
 *   RCDATA 3  payload.dll   the in-process JVMTI bootstrap, loaded with LoadLibraryW
 *
 * Why a native exe instead of a jar launcher: the launcher you would normally write needs a JRE on
 * PATH. The target machine may have none, or may have a JRE that disagrees with the client's. A
 * statically linked C exe has no runtime dependency at all beyond what Windows ships.
 *
 * Injection path (see payload.c for the in-process half):
 *   OpenProcess -> VirtualAllocEx -> WriteProcessMemory -> CreateRemoteThread(LoadLibraryW)
 * This is used for every client, not just the ones with -XX:+DisableAttachMechanism: the attach
 * protocol differs per vendor build and its failure modes are opaque, while the remote-thread call
 * either works or returns a Win32 error code that says exactly why it did not.
 *
 * Usage:
 *   BridgeTap.exe                     GUI: pick a process, inject
 *   BridgeTap.exe --list              console list of candidates
 *   BridgeTap.exe --pid 1234          inject into that pid
 *   BridgeTap.exe --auto              inject into the only candidate
 *   BridgeTap.exe 1234 payload.dll    legacy form kept for the Java injector
 *
 * Build: see native/build.sh (cl /MT, resources compiled with rc.exe)
 */

#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <tlhelp32.h>
#include <commctrl.h>
#include <shellapi.h>
#include <wctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define BT_MAX_PATH 1024
#define BT_APP_TITLE L"BridgeTap"

#define RES_AGENT 1
#define RES_HUD 2
#define RES_PAYLOAD 3

#pragma comment(lib, "comctl32.lib")

/* ------------------------------------------------------------------ logging */

static void app_dir(wchar_t *out, size_t cch) {
    wchar_t base[BT_MAX_PATH];
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", base, BT_MAX_PATH);
    if (n == 0 || n >= BT_MAX_PATH) {
        GetTempPathW(BT_MAX_PATH, base);
    }
    _snwprintf_s(out, cch, _TRUNCATE, L"%s\\BridgeTap", base);
    CreateDirectoryW(out, NULL);
}

static void log_line(const wchar_t *fmt, ...) {
    wchar_t dir[BT_MAX_PATH];
    wchar_t path[BT_MAX_PATH];
    wchar_t line[4096];
    va_list ap;
    FILE *f;

    app_dir(dir, BT_MAX_PATH);
    _snwprintf_s(path, BT_MAX_PATH, _TRUNCATE, L"%s\\injector.log", dir);

    va_start(ap, fmt);
    _vsnwprintf_s(line, 4096, _TRUNCATE, fmt, ap);
    va_end(ap);

    f = _wfopen(path, L"a, ccs=UTF-8");
    if (f != NULL) {
        SYSTEMTIME st;
        GetLocalTime(&st);
        fwprintf(f, L"%04d-%02d-%02d %02d:%02d:%02d  %s\r\n", st.wYear, st.wMonth, st.wDay,
                 st.wHour, st.wMinute, st.wSecond, line);
        fclose(f);
    }
}

/* ------------------------------------------------------------------ resources */

static int read_resource(int id, void **out, DWORD *size) {
    HRSRC found;
    HGLOBAL loaded;
    void *data;

    *out = NULL;
    *size = 0;
    found = FindResourceW(NULL, MAKEINTRESOURCEW(id), RT_RCDATA);
    if (found == NULL) {
        return 0;
    }
    *size = SizeofResource(NULL, found);
    loaded = LoadResource(NULL, found);
    if (loaded == NULL) {
        return 0;
    }
    data = LockResource(loaded);
    if (data == NULL) {
        return 0;
    }
    *out = data;
    return 1;
}

static int write_file(const wchar_t *path, const void *data, DWORD size) {
    HANDLE h = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    DWORD written = 0;
    if (h == INVALID_HANDLE_VALUE) {
        return 0;
    }
    if (!WriteFile(h, data, size, &written, NULL) || written != size) {
        CloseHandle(h);
        return 0;
    }
    CloseHandle(h);
    return 1;
}

/* The agent jar and the payload DLL stay locked by the target JVM once loaded, so every injection
   writes a fresh copy under a new name. Reusing one filename fails on the second injection with
   "the process cannot access the file". */
static void stamp(wchar_t *out, size_t cch) {
    SYSTEMTIME st;
    GetLocalTime(&st);
    _snwprintf_s(out, cch, _TRUNCATE, L"%04d%02d%02d%02d%02d%02d%03d", st.wYear, st.wMonth, st.wDay,
                 st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
}

typedef struct {
    wchar_t agent[BT_MAX_PATH];
    wchar_t hud[BT_MAX_PATH];
    wchar_t payload[BT_MAX_PATH];
    wchar_t dir[BT_MAX_PATH];
} deploy_paths;

/*
 * Housekeeping for the deploy directory.
 *
 * Every injection drops three timestamped copies (agent.jar / hud.jar / payload.dll). They cannot be
 * reused - Windows refuses to overwrite a jar the JVM still holds - so without a sweep the folder
 * grows by ~1.5 MB per injection and never shrinks.
 *
 * Keep the newest 15 files (= five injections' worth of the three parts), delete the rest. Deletion
 * failures are ignored on purpose: the client may still hold a file, and the next launch will get
 * it. bootstrap.ini does not match "bridgetap-*" and is never touched.
 */
#define BT_KEEP_DEPLOY_FILES 15

typedef struct {
    wchar_t name[BT_MAX_PATH];
    FILETIME written;
} bt_deploy_entry;

static void prune_inject(const wchar_t *dir) {
    WIN32_FIND_DATAW fd;
    wchar_t pattern[BT_MAX_PATH];
    bt_deploy_entry *list = NULL;
    size_t count = 0;
    size_t cap = 0;
    size_t i;
    size_t j;
    HANDLE h;

    _snwprintf_s(pattern, BT_MAX_PATH, _TRUNCATE, L"%s\\bridgetap-*", dir);
    h = FindFirstFileW(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) {
        return;
    }
    do {
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            continue;
        }
        if (count == cap) {
            size_t next = cap ? cap * 2 : 32;
            bt_deploy_entry *grown = (bt_deploy_entry *)realloc(list, next * sizeof(bt_deploy_entry));
            if (!grown) {
                break;
            }
            list = grown;
            cap = next;
        }
        wcscpy_s(list[count].name, BT_MAX_PATH, fd.cFileName);
        list[count].written = fd.ftLastWriteTime;
        count++;
    } while (FindNextFileW(h, &fd));
    FindClose(h);

    if (count <= BT_KEEP_DEPLOY_FILES) {
        free(list);
        return;
    }

    /* newest first, insertion sort - the list is tiny */
    for (i = 0; i < count; i++) {
        for (j = i + 1; j < count; j++) {
            if (CompareFileTime(&list[i].written, &list[j].written) < 0) {
                bt_deploy_entry tmp = list[i];
                list[i] = list[j];
                list[j] = tmp;
            }
        }
    }

    for (i = BT_KEEP_DEPLOY_FILES; i < count; i++) {
        wchar_t full[BT_MAX_PATH];
        _snwprintf_s(full, BT_MAX_PATH, _TRUNCATE, L"%s\\%s", dir, list[i].name);
        DeleteFileW(full);
    }
    free(list);
}

static int materialise(deploy_paths *paths, wchar_t *err, size_t errcch) {
    void *data = NULL;
    DWORD size = 0;
    wchar_t tag[64];

    app_dir(paths->dir, BT_MAX_PATH);
    _snwprintf_s(paths->dir, BT_MAX_PATH, _TRUNCATE, L"%s\\inject", paths->dir);
    if (!CreateDirectoryW(paths->dir, NULL) && GetLastError() != ERROR_ALREADY_EXISTS) {
        _snwprintf_s(err, errcch, _TRUNCATE, L"cannot create %s", paths->dir);
        return 0;
    }

    // Sweep the old deployments first: keep only the trio from the last five injections so the
    // directory cannot grow without bound.
    // 先扫一遍旧部署：只留最近五次注入的三件套，避免目录无限膨胀
    prune_inject(paths->dir);

    stamp(tag, 64);
    _snwprintf_s(paths->agent, BT_MAX_PATH, _TRUNCATE, L"%s\\bridgetap-agent-%s.jar", paths->dir, tag);

    if (!read_resource(RES_AGENT, &data, &size)) {
        _snwprintf_s(err, errcch, _TRUNCATE, L"embedded agent.jar missing (bad build)");
        return 0;
    }
    if (!write_file(paths->agent, data, size)) {
        _snwprintf_s(err, errcch, _TRUNCATE, L"cannot write %s", paths->agent);
        return 0;
    }

    stamp(tag, 64);
    _snwprintf_s(paths->hud, BT_MAX_PATH, _TRUNCATE, L"%s\\bridgetap-hud-%s.jar", paths->dir, tag);
    if (read_resource(RES_HUD, &data, &size) && !write_file(paths->hud, data, size)) {
        _snwprintf_s(err, errcch, _TRUNCATE, L"cannot write %s", paths->hud);
        return 0;
    }

    stamp(tag, 64);
    _snwprintf_s(paths->payload, BT_MAX_PATH, _TRUNCATE, L"%s\\bridgetap-payload-%s.dll", paths->dir, tag);
    if (!read_resource(RES_PAYLOAD, &data, &size)) {
        _snwprintf_s(err, errcch, _TRUNCATE, L"embedded payload.dll missing (bad build)");
        return 0;
    }
    if (!write_file(paths->payload, data, size)) {
        _snwprintf_s(err, errcch, _TRUNCATE, L"cannot write %s", paths->payload);
        return 0;
    }

    /* The payload cannot receive arguments through CreateRemoteThread - LoadLibraryW takes a path
       and nothing else. It reads both paths from this file next to itself.
       Written as raw UTF-8 with no BOM on purpose: a BOM would break the payload's "agent=" prefix
       match and silently load whatever jar was left there by the previous run. */
    {
        wchar_t ini[BT_MAX_PATH];
        wchar_t content[BT_MAX_PATH * 2 + 64];
        char utf8[BT_MAX_PATH * 4];
        FILE *f;
        int bytes;

        _snwprintf_s(ini, BT_MAX_PATH, _TRUNCATE, L"%s\\bootstrap.ini", paths->dir);
        _snwprintf_s(content, BT_MAX_PATH * 2 + 64, _TRUNCATE,
                     L"agent=%s\r\nhud=%s\r\n", paths->agent, paths->hud);
        bytes = WideCharToMultiByte(CP_UTF8, 0, content, -1, utf8, (int)sizeof(utf8), NULL, NULL);
        if (bytes <= 0) {
            _snwprintf_s(err, errcch, _TRUNCATE, L"cannot encode %s", ini);
            return 0;
        }
        f = _wfopen(ini, L"wb");
        if (f == NULL) {
            _snwprintf_s(err, errcch, _TRUNCATE, L"cannot write %s", ini);
            return 0;
        }
        fwrite(utf8, 1, (size_t)(bytes - 1), f);
        fclose(f);
    }
    return 1;
}

/* ------------------------------------------------------------------ injection */

typedef struct {
    int ok;
    wchar_t detail[512];
} inject_result;

static const wchar_t *win32_tip(DWORD code) {
    switch (code) {
    case ERROR_ACCESS_DENIED:
        return L"access denied - run this exe as administrator (the client is elevated)";
    case ERROR_INVALID_PARAMETER:
        return L"invalid parameter - the target may already be exiting";
    case ERROR_PARTIAL_COPY:
        return L"partial copy - architecture mismatch or protected process";
    case ERROR_NOT_ENOUGH_MEMORY:
        return L"out of memory in the target process";
    case ERROR_MOD_NOT_FOUND:
        return L"payload dll not found by the target";
    default:
        return L"see the Win32 code above";
    }
}

static inject_result inject_into(DWORD pid, const wchar_t *payload, const wchar_t *agent) {
    inject_result result;
    HANDLE process = NULL;
    HANDLE thread = NULL;
    void *remote = NULL;
    SIZE_T bytes;
    SIZE_T written = 0;
    FARPROC load_library;
    DWORD wait;
    DWORD module = 0;
    BOOL target_wow64 = FALSE;
    BOOL self_wow64 = FALSE;

    ZeroMemory(&result, sizeof(result));
    result.ok = 0;

    process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION
                              | PROCESS_VM_WRITE | PROCESS_VM_READ,
                          FALSE, pid);
    if (process == NULL) {
        _snwprintf_s(result.detail, 512, _TRUNCATE, L"OpenProcess failed (%lu): %s",
                     (unsigned long)GetLastError(), win32_tip(GetLastError()));
        return result;
    }

    IsWow64Process(process, &target_wow64);
    IsWow64Process(GetCurrentProcess(), &self_wow64);
    if (target_wow64 != self_wow64) {
        _snwprintf_s(result.detail, 512, _TRUNCATE,
                     L"bitness mismatch: this exe and the client must be the same architecture");
        CloseHandle(process);
        return result;
    }

    bytes = (wcslen(payload) + 1) * sizeof(wchar_t);
    remote = VirtualAllocEx(process, NULL, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (remote == NULL) {
        _snwprintf_s(result.detail, 512, _TRUNCATE, L"VirtualAllocEx failed (%lu): %s",
                     (unsigned long)GetLastError(), win32_tip(GetLastError()));
        CloseHandle(process);
        return result;
    }

    if (!WriteProcessMemory(process, remote, payload, bytes, &written)) {
        _snwprintf_s(result.detail, 512, _TRUNCATE, L"WriteProcessMemory failed (%lu): %s",
                     (unsigned long)GetLastError(), win32_tip(GetLastError()));
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return result;
    }

    load_library = GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW");
    if (load_library == NULL) {
        _snwprintf_s(result.detail, 512, _TRUNCATE, L"LoadLibraryW not resolvable");
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return result;
    }

    thread = CreateRemoteThread(process, NULL, 0, (LPTHREAD_START_ROUTINE)load_library, remote, 0, NULL);
    if (thread == NULL) {
        _snwprintf_s(result.detail, 512, _TRUNCATE,
                     L"CreateRemoteThread failed (%lu): %s - the client refuses remote threads",
                     (unsigned long)GetLastError(), win32_tip(GetLastError()));
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return result;
    }

    wait = WaitForSingleObject(thread, 20000);
    if (wait != WAIT_OBJECT_0) {
        _snwprintf_s(result.detail, 512, _TRUNCATE, L"the payload did not finish loading in 20 s");
        CloseHandle(thread);
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return result;
    }

    GetExitCodeThread(thread, &module);
    CloseHandle(thread);
    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    CloseHandle(process);

    if (module == 0) {
        _snwprintf_s(result.detail, 512, _TRUNCATE,
                     L"LoadLibraryW returned NULL - the payload dll or one of its imports is missing");
        return result;
    }

    result.ok = 1;
    _snwprintf_s(result.detail, 512, _TRUNCATE,
                 L"payload loaded at 0x%lx\nagent: %s", (unsigned long)module, agent);
    return result;
}

/* ------------------------------------------------------------------ process scan */

#define BT_MAX_TARGETS 64

typedef struct {
    DWORD pid;
    wchar_t image[64];
    wchar_t title[256];
    int rank;
} target_info;

typedef struct {
    target_info items[BT_MAX_TARGETS];
    int count;
} target_list;

static BOOL CALLBACK collect_window(HWND window, LPARAM param) {
    target_list *list = (target_list *)param;
    DWORD pid = 0;
    wchar_t title[256];
    int i;

    GetWindowThreadProcessId(window, &pid);
    if (pid == 0 || !IsWindowVisible(window)) {
        return TRUE;
    }
    title[0] = 0;
    GetWindowTextW(window, title, 256);
    if (title[0] == 0) {
        return TRUE;
    }
    for (i = 0; i < list->count; i++) {
        if (list->items[i].pid == pid) {
            if (wcslen(title) > wcslen(list->items[i].title)) {
                wcsncpy_s(list->items[i].title, 256, title, _TRUNCATE);
            }
            return TRUE;
        }
    }
    return TRUE;
}

static int rank_of(const wchar_t *image, const wchar_t *title) {
    wchar_t lower_image[64];
    wchar_t lower_title[256];
    int i;

    for (i = 0; image[i] != 0 && i < 63; i++) {
        lower_image[i] = (wchar_t)towlower(image[i]);
    }
    lower_image[i] = 0;
    for (i = 0; title[i] != 0 && i < 255; i++) {
        lower_title[i] = (wchar_t)towlower(title[i]);
    }
    lower_title[i] = 0;

    if (wcsstr(lower_title, L"lunar") != NULL) {
        return 3;
    }
    if (wcsstr(lower_title, L"badlion") != NULL) {
        return 3;
    }
    if (wcsstr(lower_title, L"minecraft") != NULL || wcsstr(lower_title, L"1.8.9") != NULL) {
        return 2;
    }
    /* Only a JVM is injectable. The launcher itself is an Electron app whose image name also
       contains "lunar"/"badlion", and it was being offered as a target with no way to tell them
       apart in the list. The game always runs as javaw.exe. */
    if (wcsstr(lower_image, L"javaw") != NULL || wcsstr(lower_image, L"java.exe") != NULL) {
        return 1;
    }
    return 0;
}

static int compare_targets(const void *a, const void *b) {
    const target_info *left = (const target_info *)a;
    const target_info *right = (const target_info *)b;
    if (left->rank != right->rank) {
        return right->rank - left->rank;
    }
    if (wcslen(left->title) != wcslen(right->title)) {
        return wcslen(right->title) > wcslen(left->title) ? 1 : -1;
    }
    return (left->pid > right->pid) - (left->pid < right->pid);
}

static void scan_targets(target_list *list) {
    HANDLE snapshot;
    PROCESSENTRY32W entry;
    int i;
    DWORD self = GetCurrentProcessId();

    ZeroMemory(list, sizeof(*list));
    snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return;
    }
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) {
        do {
            int rank;
            if (entry.th32ProcessID == self || entry.th32ProcessID == 0) {
                continue;
            }
            rank = rank_of(entry.szExeFile, L"");
            if (rank == 0) {
                continue;
            }
            if (list->count >= BT_MAX_TARGETS) {
                break;
            }
            list->items[list->count].pid = entry.th32ProcessID;
            wcsncpy_s(list->items[list->count].image, 64, entry.szExeFile, _TRUNCATE);
            list->items[list->count].title[0] = 0;
            list->items[list->count].rank = rank;
            list->count++;
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);

    EnumWindows(collect_window, (LPARAM)list);

    /* Titles arrive after the enumeration, so the rank is recomputed now that they are known. */
    for (i = 0; i < list->count; i++) {
        list->items[i].rank = rank_of(list->items[i].image, list->items[i].title);
    }
    /* Drop plain Java processes that expose no window: they are almost never the game. */
    for (i = 0; i < list->count; ) {
        if (list->items[i].rank <= 1 && list->items[i].title[0] == 0) {
            memmove(&list->items[i], &list->items[i + 1],
                    (size_t)(list->count - i - 1) * sizeof(target_info));
            list->count--;
        } else {
            i++;
        }
    }
    qsort(list->items, (size_t)list->count, sizeof(target_info), compare_targets);
}

/* ------------------------------------------------------------------ GUI */

static HWND g_list;
static HWND g_status;
static target_list g_targets;

static void gui_refresh(void) {
    int i;
    wchar_t line[400];

    SendMessageW(g_list, LB_RESETCONTENT, 0, 0);
    scan_targets(&g_targets);
    for (i = 0; i < g_targets.count; i++) {
        const wchar_t *kind = g_targets.items[i].rank >= 3 ? L"client"
            : g_targets.items[i].rank == 2 ? L"minecraft" : L"java";
        if (g_targets.items[i].title[0] != 0) {
            _snwprintf_s(line, 400, _TRUNCATE, L"[%s]  pid %lu  %s", kind,
                         (unsigned long)g_targets.items[i].pid, g_targets.items[i].title);
        } else {
            _snwprintf_s(line, 400, _TRUNCATE, L"[%s]  pid %lu  %s", kind,
                         (unsigned long)g_targets.items[i].pid, g_targets.items[i].image);
        }
        SendMessageW(g_list, LB_ADDSTRING, 0, (LPARAM)line);
    }
    if (g_targets.count == 0) {
        SendMessageW(g_list, LB_ADDSTRING, 0,
                     (LPARAM)L"(no client found - start Minecraft first, then press Refresh)");
    } else {
        SendMessageW(g_list, LB_SETCURSEL, 0, 0);
    }
    SetWindowTextW(g_status, g_targets.count == 0
        ? L"Start the client, reach the main menu, then press Refresh."
        : L"Select the client and press Inject (or double-click).");
}

static void gui_inject(void) {
    int index = (int)SendMessageW(g_list, LB_GETCURSEL, 0, 0);
    deploy_paths paths;
    wchar_t err[512];
    inject_result result;
    wchar_t message[1200];

    if (index < 0 || index >= g_targets.count) {
        MessageBoxW(NULL, L"Select a process first.", BT_APP_TITLE, MB_ICONINFORMATION);
        return;
    }
    SetWindowTextW(g_status, L"Injecting ...");

    if (!materialise(&paths, err, 512)) {
        log_line(L"materialise failed: %s", err);
        MessageBoxW(NULL, err, BT_APP_TITLE, MB_ICONERROR);
        SetWindowTextW(g_status, L"Failed: could not unpack the agent.");
        return;
    }
    log_line(L"inject pid=%lu agent=%s", (unsigned long)g_targets.items[index].pid, paths.agent);

    result = inject_into(g_targets.items[index].pid, paths.payload, paths.agent);
    log_line(result.ok ? L"OK: %s" : L"FAILED: %s", result.detail);

    if (result.ok) {
        _snwprintf_s(message, 1200, _TRUNCATE,
                     L"Injection delivered to pid %lu.\n\n"
                     L"The HUD appears once you are in a world. If it does not, check:\n"
                     L"%%LOCALAPPDATA%%\\BridgeTap\\agent.log\n"
                     L"%%LOCALAPPDATA%%\\BridgeTap\\inject-native.log",
                     (unsigned long)g_targets.items[index].pid);
        SetWindowTextW(g_status, L"Injected. HUD is live in game.");
        MessageBoxW(NULL, message, BT_APP_TITLE, MB_ICONINFORMATION);
    } else {
        _snwprintf_s(message, 1200, _TRUNCATE, L"Injection failed.\n\n%s\n\nLog:\n%%LOCALAPPDATA%%\\BridgeTap\\injector.log",
                     result.detail);
        SetWindowTextW(g_status, L"Failed - see the message for the Win32 reason.");
        MessageBoxW(NULL, message, BT_APP_TITLE, MB_ICONERROR);
    }
}

static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_CREATE: {
        RECT rect;
        GetClientRect(window, &rect);
        g_list = CreateWindowExW(WS_EX_CLIENTEDGE, L"LISTBOX", L"",
                                 WS_CHILD | WS_VISIBLE | WS_VSCROLL | LBS_NOTIFY,
                                 10, 10, rect.right - 20, rect.bottom - 90,
                                 window, (HMENU)1001, NULL, NULL);
        CreateWindowExW(0, L"BUTTON", L"Refresh", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                        10, rect.bottom - 70, 90, 28, window, (HMENU)1002, NULL, NULL);
        CreateWindowExW(0, L"BUTTON", L"Inject", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
                        110, rect.bottom - 70, 90, 28, window, (HMENU)1003, NULL, NULL);
        g_status = CreateWindowExW(0, L"STATIC", L"", WS_CHILD | WS_VISIBLE,
                                   210, rect.bottom - 66, rect.right - 230, 24,
                                   window, (HMENU)1004, NULL, NULL);
        gui_refresh();
        return 0;
    }
    case WM_SIZE: {
        RECT rect;
        GetClientRect(window, &rect);
        if (g_list != NULL) {
            SetWindowPos(g_list, NULL, 10, 10, rect.right - 20, rect.bottom - 90,
                         SWP_NOZORDER);
            SetWindowPos(GetDlgItem(window, 1002), NULL, 10, rect.bottom - 70, 90, 28,
                         SWP_NOZORDER);
            SetWindowPos(GetDlgItem(window, 1003), NULL, 110, rect.bottom - 70, 90, 28,
                         SWP_NOZORDER);
            SetWindowPos(g_status, NULL, 210, rect.bottom - 66, rect.right - 230, 24,
                         SWP_NOZORDER);
        }
        return 0;
    }
    case WM_COMMAND: {
        int id = LOWORD(wparam);
        if (id == 1002) {
            gui_refresh();
            return 0;
        }
        if (id == 1003) {
            gui_inject();
            return 0;
        }
        if (id == 1001 && HIWORD(wparam) == LBN_DBLCLK) {
            gui_inject();
            return 0;
        }
        return 0;
    }
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

static int run_gui(void) {
    WNDCLASSEXW wc;
    HWND window;
    MSG message;

    INITCOMMONCONTROLSEX icc;
    icc.dwSize = sizeof(icc);
    icc.dwICC = ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&icc);

    ZeroMemory(&wc, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = window_proc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.hCursor = LoadCursorW(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.lpszClassName = L"BridgeTapWindow";
    wc.hIcon = LoadIconW(NULL, IDI_APPLICATION);
    if (RegisterClassExW(&wc) == 0) {
        return 1;
    }

    window = CreateWindowExW(0, L"BridgeTapWindow", L"BridgeTap - Minecraft 1.8.9 hot injector",
                             WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 720, 460,
                             NULL, NULL, wc.hInstance, NULL);
    if (window == NULL) {
        return 1;
    }
    ShowWindow(window, SW_SHOW);
    UpdateWindow(window);

    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    return 0;
}

/* ------------------------------------------------------------------ console modes */

static int console_list(void) {
    target_list list;
    int i;
    scan_targets(&list);
    wprintf(L"Java / Minecraft processes: %d\n", list.count);
    for (i = 0; i < list.count; i++) {
        wprintf(L"  %-6lu %s\n", (unsigned long)list.items[i].pid,
                list.items[i].title[0] != 0 ? list.items[i].title : list.items[i].image);
    }
    return 0;
}

static int console_inject(DWORD pid) {
    deploy_paths paths;
    wchar_t err[512];
    inject_result result;

    if (!materialise(&paths, err, 512)) {
        wprintf(L"ERR %s\n", err);
        return 2;
    }
    result = inject_into(pid, paths.payload, paths.agent);
    log_line(result.ok ? L"OK pid=%lu: %s" : L"FAILED pid=%lu: %s", (unsigned long)pid, result.detail);
    wprintf(L"%s %s\n", result.ok ? L"OK" : L"ERR", result.detail);
    return result.ok ? 0 : 3;
}

static int console_auto(void) {
    target_list list;
    scan_targets(&list);
    if (list.count == 0) {
        wprintf(L"ERR no candidate process\n");
        return 2;
    }
    return console_inject(list.items[0].pid);
}

/*
 * Entry point is plain main() on purpose: the exe is linked as a console application so that the
 * --list / --pid modes can print, and the GUI path drops the console window on its first line.
 * A Windows-subsystem build would need a second codepath just to print four lines.
 */
int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    {
        int wide_count = 0;
        wchar_t **wide = CommandLineToArgvW(GetCommandLineW(), &wide_count);

        if (wide != NULL && wide_count >= 3 && wide[1][0] != L'-') {
            /* Legacy two-argument form: <pid> <payload.dll>, used by the Java injector build. */
            DWORD pid = (DWORD)_wtoi(wide[1]);
            inject_result result = inject_into(pid, wide[2], L"(external)");
            wprintf(L"%s %s\n", result.ok ? L"OK" : L"ERR", result.detail);
            LocalFree(wide);
            return result.ok ? 0 : 3;
        }
        if (wide != NULL && wide_count >= 2) {
            if (wcscmp(wide[1], L"--list") == 0) {
                int code = console_list();
                LocalFree(wide);
                return code;
            }
            if (wcscmp(wide[1], L"--auto") == 0) {
                int code = console_auto();
                LocalFree(wide);
                return code;
            }
            if (wide_count >= 3 && wcscmp(wide[1], L"--pid") == 0) {
                int code = console_inject((DWORD)_wtoi(wide[2]));
                LocalFree(wide);
                return code;
            }
            if (wcscmp(wide[1], L"--help") == 0 || wcscmp(wide[1], L"-h") == 0) {
                wprintf(L"BridgeTap standalone injector\n"
                        L"  (no arguments)      open the GUI\n"
                        L"  --list              list candidate processes\n"
                        L"  --pid <n>           inject into a pid\n"
                        L"  --auto              inject into the only candidate\n");
                LocalFree(wide);
                return 0;
            }
        }
        if (wide != NULL) {
            LocalFree(wide);
        }
    }

    /* Double-clicked: the console that comes with a console subsystem would sit behind the window
       for the whole session. Drop it before the GUI appears. */
    FreeConsole();
    return run_gui();
}
