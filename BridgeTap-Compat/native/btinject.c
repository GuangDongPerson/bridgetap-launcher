/*
 * BridgeTap remote injector.
 *
 * Injects a payload DLL into an already-running process with the classic four calls:
 * OpenProcess -> VirtualAllocEx -> WriteProcessMemory -> CreateRemoteThread(LoadLibraryW).
 *
 * This exists because Lunar Client starts its JVM with -XX:+DisableAttachMechanism. That flag
 * prevents HotSpot from ever creating the attach listener, so com.sun.tools.attach (and jcmd, and
 * jmap) cannot reach the process at all. Remote thread injection does not use the attach protocol,
 * so it is unaffected.
 *
 * Usage:  btinject.exe <pid> <absolute-path-to-payload.dll>
 *
 * Output is a single machine-readable line so the java side can surface a precise reason:
 *   OK
 *   ERR <stage> win32=<code> tip=<short hint>
 *
 * Build: cl /MT /O2 btinject.c /Fe:btinject.exe
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void print_tip(const char *stage, DWORD code) {
    const char *tip = "unknown";
    switch (code) {
    case ERROR_ACCESS_DENIED:
        tip = "access-denied";
        break;
    case ERROR_INVALID_PARAMETER:
        tip = "invalid-parameter";
        break;
    case ERROR_PARTIAL_COPY:
        tip = "partial-copy";
        break;
    case ERROR_NOT_ENOUGH_MEMORY:
        tip = "out-of-memory";
        break;
    case ERROR_BAD_LENGTH:
        tip = "bad-length";
        break;
    case ERROR_MOD_NOT_FOUND:
        tip = "payload-dll-not-found";
        break;
    default:
        tip = "see-win32-code";
        break;
    }
    printf("ERR %s win32=%lu tip=%s\n", stage, (unsigned long)code, tip);
    fflush(stdout);
}

static int is_wow64(HANDLE process, int *wow64) {
    BOOL result = FALSE;
    typedef BOOL(WINAPI * pfn)(HANDLE, PBOOL);
    pfn fn;
    *wow64 = 0;
    fn = (pfn)GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "IsWow64Process");
    if (fn == NULL) {
        return 0;
    }
    if (!fn(process, &result)) {
        return 0;
    }
    *wow64 = result ? 1 : 0;
    return 1;
}

int main(int argc, char **argv) {
    HANDLE process;
    HANDLE thread;
    void *remote;
    SIZE_T size;
    SIZE_T written = 0;
    FARPROC loadLibrary;
    DWORD pid;
    DWORD wait;
    DWORD exitCode = 0;
    int wow64 = 0;
    int selfWow64 = 0;
    char *end = NULL;

    if (argc < 3) {
        printf("ERR usage win32=0 tip=btinject.exe <pid> <payload.dll>\n");
        return 64;
    }

    pid = (DWORD)strtoul(argv[1], &end, 10);
    if (end == argv[1] || pid == 0) {
        printf("ERR pid-parse win32=0 tip=invalid-pid\n");
        return 64;
    }

    size = (strlen(argv[2]) + 1) * sizeof(wchar_t);

    process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION
                             | PROCESS_VM_WRITE | PROCESS_VM_READ,
                          FALSE, pid);
    if (process == NULL) {
        print_tip("open-process", GetLastError());
        return 2;
    }

    /* A 64-bit injector cannot place a thread in a 32-bit WOW64 process. */
    if (is_wow64(process, &wow64) && is_wow64(GetCurrentProcess(), &selfWow64) && wow64 != selfWow64) {
        printf("ERR bitness win32=0 tip=injector-and-target-arch-mismatch\n");
        CloseHandle(process);
        return 3;
    }

    remote = VirtualAllocEx(process, NULL, size, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (remote == NULL) {
        print_tip("virtual-alloc", GetLastError());
        CloseHandle(process);
        return 4;
    }

    {
        wchar_t wide[MAX_PATH * 4];
        if (MultiByteToWideChar(CP_ACP, 0, argv[2], -1, wide, (int)(sizeof(wide) / sizeof(wide[0]))) == 0) {
            print_tip("path-convert", GetLastError());
            VirtualFreeEx(process, remote, 0, MEM_RELEASE);
            CloseHandle(process);
            return 5;
        }
        if (!WriteProcessMemory(process, remote, wide, size, &written)) {
            print_tip("write-process-memory", GetLastError());
            VirtualFreeEx(process, remote, 0, MEM_RELEASE);
            CloseHandle(process);
            return 6;
        }
    }

    loadLibrary = GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW");
    if (loadLibrary == NULL) {
        print_tip("resolve-loadlibrary", GetLastError());
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return 7;
    }

    thread = CreateRemoteThread(process, NULL, 0, (LPTHREAD_START_ROUTINE)loadLibrary, remote, 0, NULL);
    if (thread == NULL) {
        print_tip("create-remote-thread", GetLastError());
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return 8;
    }

    wait = WaitForSingleObject(thread, 15000);
    if (wait != WAIT_OBJECT_0) {
        printf("ERR wait win32=%lu tip=remote-loadlibrary-timeout\n", (unsigned long)GetLastError());
        CloseHandle(thread);
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return 9;
    }

    GetExitCodeThread(thread, &exitCode);
    CloseHandle(thread);
    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    CloseHandle(process);

    if (exitCode == 0) {
        /* LoadLibraryW returned NULL: the DLL itself failed to map (dependencies, bitness, path). */
        printf("ERR loadlibrary-returned-null win32=126 tip=payload-dll-or-dependency-missing\n");
        return 10;
    }

    printf("OK module=0x%lx\n", (unsigned long)exitCode);
    fflush(stdout);
    return 0;
}
