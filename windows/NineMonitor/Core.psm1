Set-StrictMode -Version 2.0
function Convert-ColorNumber([string]$Text) {
    if([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $s=$Text.Normalize([Text.NormalizationForm]::FormKC).Replace([char]0x2212,'-').ToUpperInvariant()
    $pattern='(?<![0-9A-Z.,])[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?(?![0-9A-Z.,])'
    $found=[regex]::Matches($s,$pattern)
    if($found.Count -ne 1) { return $null }
    $number=0.0
    if([double]::TryParse($found[0].Value.Replace(',',''),[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$number) -and -not [double]::IsInfinity($number) -and -not [double]::IsNaN($number)) { return $number }
    return $null
}
function Test-AllThree([hashtable]$Latch,$Values,$Thresholds) {
    # Unknown is not safe: it neither triggers nor rearms an already active alert.
    foreach($name in @('Orange','Pink','White')) {
        if($null -eq $Values.$name -or [double]::IsNaN([double]$Values.$name) -or [double]::IsInfinity([double]$Values.$name)) { return $false }
    }
    $all=$true
    foreach($name in @('Orange','Pink','White')) { if([double]$Values.$name -le [double]$Thresholds.$name) { $all=$false } }
    if(-not $all) { $Latch.Active=$false; return $false }
    if(-not $Latch.Active) { $Latch.Active=$true; return $true }
    return $false
}
function Get-ScreenSignature {
    (@([Windows.Forms.Screen]::AllScreens | Sort-Object DeviceName | ForEach-Object { '{0}:{1},{2},{3},{4}' -f $_.DeviceName,$_.Bounds.X,$_.Bounds.Y,$_.Bounds.Width,$_.Bounds.Height }) -join ';')
}
function Test-UpdateManifest($Manifest,[version]$Current) {
    if($null -eq $Manifest -or $Manifest.app -ne 'NineMonitor-ThreeColor') { throw '更新資訊的程式識別不符。' }
    $version=[version]$Manifest.version
    if([string]$Manifest.sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw '更新檔案校驗碼無效。' }
    $url=[uri]$Manifest.url
    $prefix='https://github.com/rex0966445518/stock-monitor-updates/releases/download/ninemonitor-v'
    if($url.Scheme -ne 'https' -or $url.Host -ne 'github.com' -or -not $url.AbsoluteUri.StartsWith($prefix,[StringComparison]::Ordinal) -or -not $url.AbsoluteUri.EndsWith('/NineMonitor-Setup.exe',[StringComparison]::Ordinal) -or $url.Query -or $url.Fragment -or $url.UserInfo) { throw '更新下載來源無效。' }
    $tag='/ninemonitor-v'+$version.ToString()+'/'
    if(-not $url.AbsolutePath.Contains($tag)) { throw '更新版本與下載路徑不符。' }
    return $version -gt $Current
}
Export-ModuleMember -Function 'Convert-ColorNumber','Test-AllThree','Get-ScreenSignature','Test-UpdateManifest'
