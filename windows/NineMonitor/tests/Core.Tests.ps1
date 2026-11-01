$ErrorActionPreference='Stop'
Import-Module (Join-Path (Split-Path $PSScriptRoot -Parent) 'Core.psm1') -Force
$script:count=0
function Eq($a,$b,[string]$message){if($null -eq $b){if($null -ne $a){throw $message}}elseif($a -ne $b){throw ($message+': '+$a+' != '+$b)};$script:count++;Write-Host ('PASS '+$message)}
foreach($case in @(@('80.50',80.5),@('-12.25',-12.25),@('１，２３４．５０',1234.5),@('9K: 81.2',81.2),@('20.5%',20.5),@('8O',$null),@('12.3.4',$null),@('80 90',$null),@('NaN',$null),@('',$null),@('19K',$null),@('1,23',$null))){Eq (Convert-ColorNumber $case[0]) $case[1] ('parse '+$case[0])}
$l=@{Active=$false};$t=[pscustomobject]@{Orange=80;Pink=70;White=60}
function Values($o,$p,$w){[pscustomobject]@{Orange=$o;Pink=$p;White=$w}}
Eq (Test-AllThree $l (Values 81 71 60) $t) $false 'strict > rejects equality'
Eq (Test-AllThree $l (Values 81 71 59) $t) $false 'two of three is not enough'
Eq (Test-AllThree $l (Values 81 71 $null) $t) $false 'missing prevents trigger'
Eq (Test-AllThree $l (Values 81 71 61) $t) $true 'all three true triggers'
Eq (Test-AllThree $l (Values 82 72 62) $t) $false 'persistent true does not repeat'
Eq (Test-AllThree $l (Values $null 72 62) $t) $false 'missing is unknown'
Eq (Test-AllThree $l (Values 82 72 62) $t) $false 'missing does not rearm'
Eq (Test-AllThree $l (Values 80 72 62) $t) $false 'valid return to safe rearms'
Eq (Test-AllThree $l (Values 82 72 62) $t) $true 'new all-three crossing triggers'
$m=[pscustomobject]@{app='NineMonitor-ThreeColor';version='0.3.0';sha256=('a'*64);url='https://github.com/rex0966445518/stock-monitor-updates/releases/download/ninemonitor-v0.3.0/NineMonitor-Setup.exe'}
Eq (Test-UpdateManifest $m ([version]'0.2.0')) $true 'new manifest accepted'
Eq (Test-UpdateManifest $m ([version]'0.3.0')) $false 'same version is current'
Eq (Test-UpdateManifest $m ([version]'0.4.0')) $false 'no downgrade'
foreach($url in @('http://github.com/rex0966445518/stock-monitor-updates/releases/download/ninemonitor-v0.3.0/NineMonitor-Setup.exe','https://example.com/NineMonitor-Setup.exe','https://github.com/rex0966445518/stock-monitor-updates/releases/download/ninemonitor-v9.0.0/NineMonitor-Setup.exe')){
 $copy=$m.PSObject.Copy();$copy.url=$url;$rejected=$false;try{Test-UpdateManifest $copy ([version]'0.2.0')|Out-Null}catch{$rejected=$true};Eq $rejected $true 'invalid update source rejected'
}
Write-Host ($script:count.ToString()+' core/update assertions passed')
