param([switch]$SmokeTest)
#requires -Version 5.1
$ErrorActionPreference='Stop'
try {
    if($PSVersionTable.PSEdition -ne 'Desktop') { throw '請使用 Windows 內建 Windows PowerShell 5.1 啟動，不支援 PowerShell 7。' }
    if([Environment]::OSVersion.Version.Major -lt 10) { throw '需要 Windows 10 或 Windows 11。' }
    & (Join-Path $PSScriptRoot 'Monitor.ps1') -SmokeTest:$SmokeTest
} catch {
    $detail=$_.Exception.Message+"`r`n"+$_.ScriptStackTrace
    try {
        $folder=Join-Path $env:LOCALAPPDATA 'NineMonitor\Data'; [void][IO.Directory]::CreateDirectory($folder)
        [IO.File]::WriteAllText((Join-Path $folder 'startup-error.txt'),$detail,(New-Object Text.UTF8Encoding($true)))
    } catch {}
    if($SmokeTest) { throw }
    Add-Type -AssemblyName System.Windows.Forms
    [void][Windows.Forms.MessageBox]::Show(('啟動失敗：'+$detail),'三色數值監視器',[Windows.Forms.MessageBoxButtons]::OK,[Windows.Forms.MessageBoxIcon]::Error)
}
