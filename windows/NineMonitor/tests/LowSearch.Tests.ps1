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

# Exercise the actual start-search orchestration without injecting desktop input.
Add-Type -TypeDefinition (Get-Content (Join-Path $root 'Native.cs') -Raw -Encoding UTF8) -ReferencedAssemblies 'System.Drawing.dll','System.Windows.Forms.dll'
$script:form=New-Object Windows.Forms.Form
$script:form.Show()
$script:signature=Get-ScreenSignature
$script:searchRanges=$r
$script:names=@('Orange','Pink','White')
$script:labels=@{Orange='橘';Pink='紫';White='白'}
$script:channels=@{Orange=@{Region=(New-Object Drawing.Rectangle(30,300,180,24))}}
$script:borders=@();$script:searchWindow=$null;$script:generation=0
$script:clock=[Diagnostics.Stopwatch]::StartNew()
$script:hotkey=New-Object PSObject
$script:hotkey|Add-Member ScriptMethod Register {return $true}
$script:rightInput=New-Object PSObject
$script:rightInput|Add-Member ScriptMethod SelectPoint {param($p) if($p.X -ne 120 -or $p.Y -ne 200){throw 'Wrong target coordinate'};$script:selected=$true;return $true}
$script:rightPump=New-Object PSObject
$script:rightPump|Add-Member ScriptMethod Start {param($delay) if(-not $script:selected -or $delay -ne 200){throw 'Input started before target selection'};$script:pumpStarted=$true}
function Save-Settings {}
function Controls {}
function Status($text,$errorFlag) {$script:statusText=$text}
function Show-App {$form.WindowState='Normal'}
function Stop-Monitor {
 if($script:searchWindow){$script:searchWindow.Close();$script:searchWindow.Dispose();$script:searchWindow=$null}
 foreach($b in $script:borders){$b.Close();$b.Dispose()};$script:borders=@();$script:active=$false
 [void][NineMonitor.Desktop]::SetThreadExecutionState(2147483648)
}
function Get-LowSearchPoint {
 if($form.WindowState -ne 'Minimized'){throw 'Main form was not minimized before selecting coordinate'}
 return New-Object Drawing.Point(120,200)
}
try {
 Start-LowSearch
 Assert ($script:pumpStarted -and $script:active -and $script:searchMode) 'coordinate selection precedes automatic right-arrow start'
 Assert ($form.WindowState -eq 'Minimized') 'main form remains minimized after start'
 Assert ($script:searchWindow.Visible -and $null -eq $script:searchWindow.Owner) 'green search window stays visible independently of minimized main form'
 Assert ($script:borders.Count -eq 1 -and $script:borders[0].Visible) 'one shared green numeric border remains visible'
 Assert (-not $script:searchWindow.Bounds.IntersectsWith($channels.Orange.Region)) 'search window does not cover OCR region'
 $script:searchWindow.UpdateProgress(25);$script:searchWindow.UpdateValues('20 / 11 / -10')
 [Windows.Forms.Application]::DoEvents()
 Assert ($script:searchWindow.Visible) 'updating progress and numbers retains green search window'
 $button=@($script:searchWindow.Controls|Where-Object {$_ -is [Windows.Forms.Button]})[0]
 $button.PerformClick()
 Assert ($null -eq $script:searchWindow -and $script:borders.Count -eq 0 -and -not $script:active) 'green window stop button closes overlays and stops search'
 function Get-LowSearchPoint {return $null}
 $script:pumpStarted=$false
 Start-LowSearch
 Assert (-not $script:pumpStarted -and $null -eq $script:searchWindow -and $form.WindowState -eq 'Normal') 'cancel coordinate selection restores main page without sending keys'
} finally {Stop-Monitor;$form.Dispose()}
