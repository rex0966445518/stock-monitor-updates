param([string]$Current,[string]$Core)
$ErrorActionPreference='Stop';$path=$null
try{
    Import-Module -Name $Core -Force
    [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
    $manifest='https://github.com/rex0966445518/stock-monitor-updates/releases/download/ninemonitor-latest/ninemonitor-update.json'
    $m=Invoke-RestMethod -Uri ($manifest+'?t='+[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) -TimeoutSec 20 -Headers @{'User-Agent'='NineMonitor-Updater'}
    if(-not (Test-UpdateManifest $m ([version]$Current))){[pscustomobject]@{State='Current'};return}
    $folder=Join-Path ([IO.Path]::GetTempPath()) ('NineMonitor-Update-'+[guid]::NewGuid().ToString('N'));[void][IO.Directory]::CreateDirectory($folder)
    $path=Join-Path $folder 'NineMonitor-Setup.exe'
    Invoke-WebRequest -UseBasicParsing -Uri $m.url -OutFile $path -TimeoutSec 120 -Headers @{'User-Agent'='NineMonitor-Updater'}
    $hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if($hash -ne $m.sha256){throw '下載檔案校驗失敗，未執行安裝。'}
    $file=[IO.File]::OpenRead($path);try{if($file.ReadByte() -ne 77 -or $file.ReadByte() -ne 90){throw '更新檔案不是有效的 Windows EXE。'}}finally{$file.Dispose()}
    [pscustomobject]@{State='Ready';Version=[string]$m.version;Path=$path}
}catch{if($path){Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue};[pscustomobject]@{State='Error';Error=$_.Exception.GetBaseException().Message}}
