$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$root=Split-Path $PSScriptRoot -Parent
$fake=@'
namespace NineMonitor.Tests {
    public sealed class FakeRightInput : NineMonitor.IRightInput {
        public volatile bool Valid=true, Focus=true, Escape=false, Modifier=false, Accept=true;
        public int Count;
        public bool CanArm(out string title){title="Controlled test target";return Valid;}
        public bool StillTarget(){return Focus;}
        public bool EscapePressed(){return Escape;}
        public bool ModifiersPressed(){return Modifier;}
        public bool SendRight(){if(!Accept)return false;System.Threading.Interlocked.Increment(ref Count);return true;}
    }
}
'@
Add-Type -TypeDefinition ((Get-Content (Join-Path $root 'Native.cs') -Raw -Encoding UTF8)+$fake) -ReferencedAssemblies 'System.Drawing.dll','System.Windows.Forms.dll'
function Assert($value,[string]$message){if(-not $value){throw $message};Write-Host ('PASS '+$message)}
Assert ([NineMonitor.WindowsRightInput]::InputSize -eq 40) 'x64 SendInput structure matches Win32 ABI'
$s=New-Object NineMonitor.RightCadence;$count=0
for($t=0;$t -le 1000;$t+=5){if($s.Due($t)){$count++}}
Assert ($count -eq 5) 'exactly five scheduled right presses per second'
Assert ($s.Due(10000)) 'overdue scheduler advances once'
Assert (-not $s.Due(10001)) 'no burst catch-up after stalled processing'
$f=New-Object NineMonitor.Tests.FakeRightInput;$p=New-Object NineMonitor.AutoRightPump($f)
try{
    $p.Start(0);Start-Sleep -Milliseconds 1120
    Assert ($f.Count -ge 4 -and $f.Count -le 6) 'background keyboard pump runs while main thread is busy'
    $f.Focus=$false;Start-Sleep -Milliseconds 80
    Assert (-not $p.Running -and $p.StopReason -eq 'focus') 'focus change stops automatic keys'
    $last=$f.Count;Start-Sleep -Milliseconds 250;Assert ($f.Count -eq $last) 'no keys after focus stop'
    $p.Stop();$f.Focus=$true;$f.Escape=$true;$p.Start(0);Start-Sleep -Milliseconds 80
    Assert (-not $p.Running -and $p.StopReason -eq 'escape') 'ESC stops from keyboard worker'
    $f.Escape=$false;$f.Modifier=$true;$p.Start(0);Start-Sleep -Milliseconds 80
    Assert (-not $p.Running -and $p.StopReason -eq 'modifier') 'modifier keys do not become modified right arrows'
    $f.Modifier=$false;$f.Valid=$false;$p.Start(0);Start-Sleep -Milliseconds 80
    Assert (-not $p.Running -and $p.StopReason -eq 'target') 'invalid target rejected'
    $f.Valid=$true;$p.Start(350);Start-Sleep -Milliseconds 120
    $last=$f.Count;$p.Stop();Start-Sleep -Milliseconds 400
    Assert ($f.Count -eq $last) 'stopping countdown prevents all later key injection'
    $f.Accept=$false;$p.Start(0);Start-Sleep -Milliseconds 300
    Assert (-not $p.Running -and $p.StopReason -eq 'input') 'input rejection stops rather than looping'
}finally{$p.Dispose()}
Write-Host 'Auto-right cadence, concurrency and stop tests passed. Live target responsiveness remains app-dependent.'
