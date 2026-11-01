$ErrorActionPreference='Stop'
$base=[string]$env:NM_SETUP_DIR
try{
    $payload=Join-Path $base 'payload'
    Expand-Archive -LiteralPath (Join-Path $base 'payload.zip') -DestinationPath $payload -Force
    & (Join-Path $payload 'Install.ps1')
    exit $LASTEXITCODE
}catch{
    Add-Type -AssemblyName System.Windows.Forms
    [void][Windows.Forms.MessageBox]::Show(('無法完成安裝：'+$_.Exception.Message),'三色數值監視器')
    exit 1
}finally{
    if($payload -and (Test-Path $payload)){Remove-Item -LiteralPath $payload -Recurse -Force -ErrorAction SilentlyContinue}
}
