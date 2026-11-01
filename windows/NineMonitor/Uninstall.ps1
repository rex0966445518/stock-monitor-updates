$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
if([Windows.Forms.MessageBox]::Show('請先關閉程式。移除三色數值監視器？設定和歷史紀錄會保留。','移除程式','YesNo','Question') -ne 'Yes'){exit}
try{
    $target=Join-Path $env:LOCALAPPDATA 'NineMonitor\App'
    if(Test-Path $target){Remove-Item -LiteralPath $target -Recurse -Force}
    foreach($folder in @([Environment]::GetFolderPath('Desktop'),(Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs'))){$link=Join-Path $folder '三色數值監視器.lnk';if(Test-Path $link){Remove-Item -LiteralPath $link -Force}}
    Remove-Item -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\NineMonitor-ThreeColor' -Force -ErrorAction SilentlyContinue
    [void][Windows.Forms.MessageBox]::Show('已移除程式，設定與歷史紀錄仍保留在個人 AppData。')
}catch{[void][Windows.Forms.MessageBox]::Show(('移除未完成，請關閉監視器後重試：'+$_.Exception.Message))}
