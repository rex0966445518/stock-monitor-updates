$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition (Get-Content (Join-Path $root 'Native.cs') -Raw -Encoding UTF8) -ReferencedAssemblies 'System.Drawing.dll','System.Windows.Forms.dll'
function Assert($ok,[string]$message){if(-not $ok){throw $message};Write-Host ('PASS '+$message)}
$bounds=New-Object Drawing.Size(400,250)
$r=[NineMonitor.SelectionGeometry]::Build((New-Object Drawing.Point(90,80)),(New-Object Drawing.Point(45,68)),$bounds)
Assert ($r.X -eq 45 -and $r.Y -eq 68 -and $r.Width -eq 45 -and $r.Height -eq 12) 'reverse drag keeps small number height'
Assert ([NineMonitor.SelectionGeometry]::Valid($r)) '12-pixel-high chart values are accepted'
$r=[NineMonitor.SelectionGeometry]::Build((New-Object Drawing.Point(3,2)),(New-Object Drawing.Point(-20,-20)),$bounds)
Assert ($r.X -eq 0 -and $r.Y -eq 0 -and -not [NineMonitor.SelectionGeometry]::Valid($r)) 'screen edge is clamped and accidental clicks rejected'
$bitmap=New-Object Drawing.Bitmap(400,250)
function New-TestSelector {New-Object NineMonitor.Selector($bitmap,(New-Object Drawing.Rectangle(0,0,400,250)))}
function Mouse($form,[string]$method,[int]$x,[int]$y){
    $args=New-Object Windows.Forms.MouseEventArgs([Windows.Forms.MouseButtons]::Left,1,$x,$y,0)
    [void]$form.GetType().GetMethod($method,[Reflection.BindingFlags]'NonPublic,Instance').Invoke($form,@($args))
}
$f=New-TestSelector
try{
    $f.Add_Shown({Mouse $this 'OnMouseDown' 50 100;Mouse $this 'OnMouseMove' 94 111;Mouse $this 'OnMouseUp' 97 113})
    Assert ($f.ShowDialog() -eq 'OK') 'release automatically closes selection dialog'
    Assert ($f.Selected.Width -eq 47 -and $f.Selected.Height -eq 13) 'release coordinates win over last mouse-move position'
}finally{$f.Dispose()}
$f=New-TestSelector
try{
    $f.Add_Shown({
        Mouse $this 'OnMouseDown' 30 90;Mouse $this 'OnMouseUp' 32 92
        if($this.DialogResult -eq 'OK'){throw 'tiny accidental selection must not be accepted'}
        Mouse $this 'OnMouseDown' 120 110;Mouse $this 'OnMouseUp' 70 100
    })
    Assert ($f.ShowDialog() -eq 'OK' -and $f.Selected.Height -eq 10) 'invalid tiny selection can be redrawn without getting stuck'
}finally{$f.Dispose()}
$f=New-TestSelector
try{
    $f.Add_Shown({
        Mouse $this 'OnMouseDown' 30 90;Mouse $this 'OnMouseMove' 75 102
        # Simulate a lost mouse-up message, then the release watchdog callback.
        $field=$this.GetType().GetField('releaseWatch',[Reflection.BindingFlags]'NonPublic,Instance')
        $watch=$field.GetValue($this)
        [Windows.Forms.Cursor]::Position=$this.PointToScreen((New-Object Drawing.Point(78,104)))
        [void]$watch.GetType().GetMethod('OnTick',[Reflection.BindingFlags]'NonPublic,Instance').Invoke($watch,@([EventArgs]::Empty))
    })
    Assert ($f.ShowDialog() -eq 'OK') 'release watchdog recovers lost mouse-up'
    Assert ($f.Selected.Width -eq 48 -and $f.Selected.Height -eq 14) 'watchdog uses actual cursor release location'
}finally{$f.Dispose()}
$f=New-TestSelector
try{
    $f.Add_Shown({
        Mouse $this 'OnMouseDown' 40 80;Mouse $this 'OnMouseMove' 92 94
        [Windows.Forms.Cursor]::Position=$this.PointToScreen((New-Object Drawing.Point(92,94)))
        $key=New-Object Windows.Forms.KeyEventArgs([Windows.Forms.Keys]::Enter)
        [void]$this.GetType().GetMethod('OnKeyDown',[Reflection.BindingFlags]'NonPublic,Instance').Invoke($this,@($key))
    })
    Assert ($f.ShowDialog() -eq 'OK' -and $f.Selected.Height -eq 14) 'Enter manually confirms the current small selection'
}finally{$f.Dispose()}
$f=New-TestSelector
try{
    $f.Add_Shown({
        Mouse $this 'OnMouseDown' 40 80
        $key=New-Object Windows.Forms.KeyEventArgs([Windows.Forms.Keys]::Escape)
        [void]$this.GetType().GetMethod('OnKeyDown',[Reflection.BindingFlags]'NonPublic,Instance').Invoke($this,@($key))
    })
    Assert ($f.ShowDialog() -eq 'Cancel' -and $f.Selected.IsEmpty) 'ESC cancels without saving any region'
}finally{$f.Dispose()}
$bitmap.Dispose()
Write-Host 'Selector small-height, reverse-drag, release and watchdog regression tests passed'
