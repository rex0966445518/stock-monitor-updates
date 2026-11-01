#requires -Version 5.1
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
$appRoot=Join-Path $env:LOCALAPPDATA 'NineMonitor'
$target=Join-Path $appRoot 'App'
$stage=Join-Path $appRoot ('App-stage-'+[guid]::NewGuid().ToString('N'))
$backup=Join-Path $appRoot ('App-backup-'+[guid]::NewGuid().ToString('N'))
$argsText=[string]$env:NM_SETUP_ARGS
$silent=$argsText -match '/(UPDATE|SILENT)\b';$noLaunch=$argsText -match '/NOLAUNCH\b'
try{
    if([Environment]::OSVersion.Version.Major -lt 10){throw '需要 Windows 10 或 Windows 11。'}
    if(-not $silent){$answer=[Windows.Forms.MessageBox]::Show('將安裝三色數值監視器，並建立桌面捷徑。設定及歷史紀錄會保留。要繼續嗎？','三色數值監視器安裝','YesNo','Question');if($answer -ne 'Yes'){exit 0}}
    if($argsText -match '/WAITPID=(\d+)'){$parentId=[int]$Matches[1];if($parentId -gt 0 -and (Get-Process -Id $parentId -ErrorAction SilentlyContinue)){Wait-Process -Id $parentId -Timeout 45 -ErrorAction Stop}}
    [void][IO.Directory]::CreateDirectory($stage)
    foreach($file in @('NineMonitor.exe','Launch.ps1','Monitor.ps1','Core.psm1','OcrWorker.ps1','Native.cs','UpdateWorker.ps1','Uninstall.ps1','version.json','README.txt')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $stage $file) -Force}
    $renamed=$false
    if(Test-Path $target){for($i=0;$i -lt 15;$i++){try{[IO.Directory]::Move($target,$backup);$renamed=$true;break}catch{Start-Sleep -Milliseconds 300}};if(-not $renamed){throw '舊版程式仍在使用，請關閉監視器後重新安裝。'}}
    try{[IO.Directory]::Move($stage,$target)}catch{if($renamed -and -not (Test-Path $target)){[IO.Directory]::Move($backup,$target)};throw}
    Add-Type -TypeDefinition (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'SetupShell.cs') -Raw -Encoding UTF8)
    foreach($folder in @([Environment]::GetFolderPath('Desktop'),(Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs'))){[void][IO.Directory]::CreateDirectory($folder);[NineMonitor.SetupShell]::Create((Join-Path $folder '三色數值監視器.lnk'),(Join-Path $target 'NineMonitor.exe'),$target)}
    $key='HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\NineMonitor-ThreeColor';[void](New-Item -Path $key -Force)
    $version=(Get-Content -LiteralPath (Join-Path $target 'version.json') -Raw -Encoding UTF8|ConvertFrom-Json).version
    $powershell=Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    foreach($pair in @(@('DisplayName','三色數值監視器'),@('DisplayVersion',[string]$version),@('Publisher','雷允澤'),@('InstallLocation',$target),@('UninstallString',('"'+$powershell+'" -NoProfile -STA -ExecutionPolicy Bypass -File "'+(Join-Path $target 'Uninstall.ps1')+'"')))){[void](New-ItemProperty -Path $key -Name $pair[0] -Value $pair[1] -PropertyType String -Force)}
    if(Test-Path $backup){Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue}
    if(-not $noLaunch){Start-Process -FilePath (Join-Path $target 'NineMonitor.exe')}
    exit 0
}catch{
    if(Test-Path $stage){Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue}
    $detail=$_.Exception.GetBaseException().Message
    if($env:NM_CI_TEST -eq '1'){Write-Host $detail;exit 1}
    if($silent){[void][Windows.Forms.MessageBox]::Show(('更新／安裝未完成：'+$detail),'三色數值監視器')}else{[void][Windows.Forms.MessageBox]::Show(('安裝失敗：'+$detail),'三色數值監視器')}
    exit 1
}
