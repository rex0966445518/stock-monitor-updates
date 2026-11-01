#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <wchar.h>
#ifdef SETUP_BUILD
#include "payload.h"
#include "bootstrap.h"
static int write_file(const wchar_t *path,const unsigned char *bytes,DWORD size){
    HANDLE h=CreateFileW(path,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);
    if(h==INVALID_HANDLE_VALUE)return 0;DWORD written=0;BOOL ok=WriteFile(h,bytes,size,&written,NULL);CloseHandle(h);return ok&&written==size;
}
#endif
static int spawn(const wchar_t *script,int smoke){
    wchar_t system[MAX_PATH],exe[MAX_PATH],command[32768];GetSystemDirectoryW(system,MAX_PATH);
    _snwprintf(exe,MAX_PATH,L"%ls\\WindowsPowerShell\\v1.0\\powershell.exe",system);
    _snwprintf(command,32768,L"\"%ls\" -NoLogo -NoProfile -STA -ExecutionPolicy Bypass -WindowStyle Hidden -File \"%ls\"%ls",exe,script,smoke?L" -SmokeTest":L"");
    wchar_t local[32768],logdir[32768],logpath[32768];GetEnvironmentVariableW(L"LOCALAPPDATA",local,32768);
    _snwprintf(logdir,32768,L"%ls\\NineMonitor",local);CreateDirectoryW(logdir,NULL);
    _snwprintf(logdir,32768,L"%ls\\NineMonitor\\Data",local);CreateDirectoryW(logdir,NULL);
#ifdef SETUP_BUILD
    _snwprintf(logpath,32768,L"%ls\\installer-native.log",logdir);
#else
    _snwprintf(logpath,32768,L"%ls\\app-launch.log",logdir);
#endif
    SECURITY_ATTRIBUTES sa={sizeof(sa),NULL,TRUE};
    HANDLE log=CreateFileW(logpath,GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,&sa,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);
    STARTUPINFOW si={0};PROCESS_INFORMATION pi={0};si.cb=sizeof(si);si.dwFlags=STARTF_USESHOWWINDOW;si.wShowWindow=SW_HIDE;
    if(log!=INVALID_HANDLE_VALUE){si.dwFlags|=STARTF_USESTDHANDLES;si.hStdOutput=log;si.hStdError=log;si.hStdInput=GetStdHandle(STD_INPUT_HANDLE);}
    if(!CreateProcessW(exe,command,NULL,NULL,TRUE,CREATE_NO_WINDOW,NULL,NULL,&si,&pi)){MessageBoxW(NULL,L"無法啟動 Windows PowerShell。請確認 Windows 內建元件可用。",L"三色數值監視器",MB_OK|MB_ICONERROR);return 1;}
    WaitForSingleObject(pi.hProcess,INFINITE);DWORD code=1;GetExitCodeProcess(pi.hProcess,&code);CloseHandle(pi.hProcess);CloseHandle(pi.hThread);if(log!=INVALID_HANDLE_VALUE)CloseHandle(log);return (int)code;
}
int WINAPI wWinMain(HINSTANCE h,HINSTANCE prev,PWSTR args,int show){
#ifdef SETUP_BUILD
    wchar_t temp[MAX_PATH],dir[MAX_PATH],zip[MAX_PATH],script[MAX_PATH];
    if(!GetTempPathW(MAX_PATH,temp)||!GetTempFileNameW(temp,L"NM3",0,dir))return 1;
    DeleteFileW(dir);if(!CreateDirectoryW(dir,NULL))return 1;
    _snwprintf(zip,MAX_PATH,L"%ls\\payload.zip",dir);_snwprintf(script,MAX_PATH,L"%ls\\bootstrap.ps1",dir);
    if(!write_file(zip,payload_bytes,(DWORD)sizeof(payload_bytes))||!write_file(script,bootstrap_bytes,(DWORD)sizeof(bootstrap_bytes)))return 1;
    SetEnvironmentVariableW(L"NM_SETUP_DIR",dir);SetEnvironmentVariableW(L"NM_SETUP_ARGS",GetCommandLineW());
    int result=spawn(script,0);DeleteFileW(zip);DeleteFileW(script);RemoveDirectoryW(dir);return result;
#else
    wchar_t own[32768],script[32768];if(!GetModuleFileNameW(NULL,own,32768))return 1;
    wchar_t *slash=wcsrchr(own,L'\\');if(!slash)return 1;*slash=0;
    _snwprintf(script,32768,L"%ls\\Launch.ps1",own);
    return spawn(script,wcsstr(GetCommandLineW(),L"/SMOKE")!=NULL);
#endif
}
