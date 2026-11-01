$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $root 'Core.psm1') -Force
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
. (Join-Path $root 'LowSearch.ps1')
function Assert($ok,[string]$message){if(-not $ok){throw $message};Write-Host ('PASS '+$message)}
$r=[pscustomobject]@{Orange=[pscustomobject]@{Min=19;Max=21};Pink=[pscustomobject]@{Min=10;Max=12};White=[pscustomobject]@{Min=-20;Max=0}}
function V($o,$p,$w){[pscustomobject]@{Orange=$o;Pink=$p;White=$w}}
$l=@{Active=$false}
Assert (Test-ThreeRanges $l (V 19 10 -20) $r) 'all three lower endpoints included'
Assert (-not (Test-ThreeRanges $l (V 21 12 0) $r)) 'upper endpoints remain in range without repeated speech'
Assert (-not (Test-ThreeRanges $l (V 21.01 11 -10) $r)) 'one out-of-range value prevents all-three match'
Assert (-not (Test-ThreeRanges $l (V 20 11 0.01) $r)) 'negative-to-zero range rejects positive values'
Assert (Test-ThreeRanges $l (V 20 11 -10) $r) 'new all-three range entry triggers'
Assert (-not (Test-ThreeRanges $l (V $null 11 -10) $r)) 'missing OCR is not a match'
Assert (-not (Test-ThreeRanges $l (V 20 11 -10) $r)) 'missing OCR does not rearm'
$bad=[pscustomobject]@{Orange=[pscustomobject]@{Min=22;Max=19};Pink=$r.Pink;White=$r.White}
$rejected=$false;try{Assert-RangeSettings $bad}catch{$rejected=$true};Assert $rejected 'reversed interval rejected'
$bad.Orange.Min=[double]::NaN;$rejected=$false;try{Assert-RangeSettings $bad}catch{$rejected=$true};Assert $rejected 'non-finite interval rejected'
$d=New-LowSearchDialog $r $true
try{
 Assert ($d.Tag.Inputs.Orange.Min.Value -eq 19 -and $d.Tag.Inputs.White.Min.Value -eq -20 -and $d.Tag.Inputs.White.Max.Value -eq 0) 'dialog displays requested defaults'
 $d.Add_Shown({$this.Tag.Start.PerformClick()})
 Assert ($d.ShowDialog() -eq 'OK') 'start-search button validates and submits range dialog'
 Assert ($d.Tag.Selected.Pink.Min -eq 10 -and $d.Tag.Selected.Pink.Max -eq 12 -and $d.Tag.Pause.Checked) 'range dialog preserves bounds and pause option'
}finally{$d.Dispose()}
Write-Host 'Low-point range and dialog tests passed'
