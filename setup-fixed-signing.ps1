# Run from Windows PowerShell with JDK 17+ and GitHub CLI installed.
# Creates a private local signing key, uploads only to encrypted GitHub Actions secrets.
# Never commit the key file or send its password to anyone.
$ErrorActionPreference = 'Stop'
$repo = 'rex0966445518/stock-monitor-updates'
$branch = 'short-radar-v0.3.2'
$alias = 'short-radar-release'
$names = @('SHORT_RADAR_KEYSTORE_B64','SHORT_RADAR_STORE_PASSWORD','SHORT_RADAR_KEY_ALIAS','SHORT_RADAR_KEY_PASSWORD','SHORT_RADAR_CERT_SHA256')
Get-Command gh,keytool -ErrorAction Stop | Out-Null
& gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) { & gh auth login --hostname github.com --web; if ($LASTEXITCODE -ne 0) { throw 'GitHub login failed.' } }
$existing = & gh secret list --repo $repo
if ($LASTEXITCODE -ne 0) { throw 'Cannot list secrets. Check repository admin permissions.' }
foreach ($n in $names) { if ($existing -match ("^" + $n + "\s")) { throw "Signing secret already exists: $n. Stopping to prevent accidental key rotation. If setup is complete, push a new commit to the release branch." } }
$dir = Join-Path $HOME 'ShortRadarSigning'
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$key = Join-Path $dir 'short-radar-release.p12'
$secure = Read-Host 'Enter a strong signing password (keep it safe; use 16+ characters)' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $pass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
  if ($pass.Length -lt 16) { throw 'Use at least 16 characters.' }
  $env:SHORT_RADAR_LOCAL_PASSWORD = $pass
  if (!(Test-Path $key)) {
    & keytool -genkeypair -storetype PKCS12 -keystore $key -alias $alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Short Radar Release' -storepass:env SHORT_RADAR_LOCAL_PASSWORD -keypass:env SHORT_RADAR_LOCAL_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
  }
  $pub = Join-Path $dir 'short-radar-release.der'
  & keytool -exportcert -storetype PKCS12 -keystore $key -alias $alias -storepass:env SHORT_RADAR_LOCAL_PASSWORD -file $pub
  if ($LASTEXITCODE -ne 0) { throw 'Key or password invalid.' }
  $fingerprint = (Get-FileHash -Algorithm SHA256 -Path $pub).Hash.ToLowerInvariant()
  function Set-GhSecret([string]$Name,[string]$Value) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Get-Command gh).Source
    $psi.Arguments = "secret set $Name --repo $repo"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.Write($Value)
    $proc.StandardInput.Close()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) { throw "Failed setting $Name. Do not generate a replacement key; retain the existing local key." }
  }
  Set-GhSecret 'SHORT_RADAR_KEYSTORE_B64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($key)))
  Set-GhSecret 'SHORT_RADAR_STORE_PASSWORD' $pass
  Set-GhSecret 'SHORT_RADAR_KEY_ALIAS' $alias
  Set-GhSecret 'SHORT_RADAR_KEY_PASSWORD' $pass
  Set-GhSecret 'SHORT_RADAR_CERT_SHA256' $fingerprint
  Write-Host "Fixed signing configured. Public certificate SHA256: $fingerprint"
  Write-Host "Back up the private key securely: $key"
  $marker = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("Signing initialized. Public certificate SHA256: $fingerprint"))
  & gh api --method PUT "repos/$repo/contents/signing-ready.txt" -f "message=Initialize fixed signing and trigger build" -f "content=$marker" -f "branch=$branch" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Secrets configured but build trigger failed. Push a new commit to the release branch.' }
  Write-Host "Build triggered: https://github.com/$repo/actions?query=branch%3A$branch"
} finally {
  Remove-Item Env:SHORT_RADAR_LOCAL_PASSWORD -ErrorAction SilentlyContinue
  $pass = $null
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}