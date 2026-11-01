$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition (Get-Content -LiteralPath (Join-Path $root 'Native.cs') -Raw -Encoding UTF8) -ReferencedAssemblies 'System.Windows.Forms.dll','System.Drawing.dll'
foreach($case in @(@([Drawing.Color]::Orange,'Orange',$true),@([Drawing.Color]::HotPink,'Pink',$true),@([Drawing.Color]::MediumPurple,'Pink',$true),@([Drawing.Color]::White,'White',$true),@([Drawing.Color]::Black,'White',$false),@([Drawing.Color]::Blue,'Orange',$false),@([Drawing.Color]::Orange,'Pink',$false))){if([NineMonitor.ColorFilter]::Matches($case[0],$case[1],-1) -ne $case[2]){throw ('Color filter failed: '+$case[1])}}
$canvas=New-Object Drawing.Bitmap(400,240);$g=[Drawing.Graphics]::FromImage($canvas);$g.Clear([Drawing.Color]::Black)
$font=New-Object Drawing.Font('Segoe UI',28,[Drawing.FontStyle]::Bold)
$g.DrawString('81.50',$font,[Drawing.Brushes]::Orange,10,4);$g.DrawString('72.25',$font,[Drawing.Brushes]::HotPink,10,84);$g.DrawString('63.75',$font,[Drawing.Brushes]::White,10,164);$g.Dispose();$font.Dispose()
$items=@();$i=0
foreach($name in @('Orange','Pink','White')){$count=0;$r=New-Object Drawing.Rectangle(0,($i*80),300,80);$b=[NineMonitor.ColorFilter]::Mask($canvas,$r,$name,-1,[ref]$count);if($count -lt 10){throw 'Masked image is empty'};$path=Join-Path $env:TEMP ('NM3-Test-'+$name+'.png');$b.Save($path,[Drawing.Imaging.ImageFormat]::Png);$b.Dispose();$items+=@{Name=$name;Path=$path;Pixels=$count};$i++};$canvas.Dispose()
Write-Host 'Color filtering and pixel masking passed'
$r=& (Join-Path $root 'OcrWorker.ps1') (@{Core=(Join-Path $root 'Core.psm1');Items=$items}|ConvertTo-Json -Compress -Depth 5)
if($r.Error -match '沒有可用|not registered|not supported|無法載入|找不到類型|0x80040154'){
 Write-Host ('OCR unavailable on this CI image; device OCR test remains pending: '+$r.Error)
}else{
 if($r.Error){throw $r.Error}
 foreach($pair in @(@('Orange',81.5),@('Pink',72.25),@('White',63.75))){if($r.Values.($pair[0]) -ne $pair[1]){throw ('Synthetic OCR mismatch '+$pair[0]+': '+($r|ConvertTo-Json -Depth 6))}}
 Write-Host 'Synthetic three-color OCR passed'
}
