param([string]$RequestJson)
$ErrorActionPreference='Stop'
$request=$RequestJson|ConvertFrom-Json
$paths=@($request.Items|ForEach-Object{$_.Path})
$values=[ordered]@{Orange=$null;Pink=$null;White=$null}
$texts=[ordered]@{Orange='';Pink='';White=''}
$errors=New-Object 'System.Collections.Generic.List[string]'
try {
    Import-Module -Name $request.Core -Force
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null=[Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
    $null=[Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
    $null=[Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]
    $null=[Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
    $null=[Windows.Graphics.Imaging.SoftwareBitmap,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
    $null=[Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime]
    $asTask=@([System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{$_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetGenericArguments().Count -eq 1 -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0]
    function Await($operation,[type]$type) {
        $task=$asTask.MakeGenericMethod($type).Invoke($null,@($operation))
        if(-not $task.Wait(5000)){throw 'Windows OCR 作業逾時'}
        return $task.Result
    }
    $engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('en-US'))
    if(-not $engine){$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()}
    if(-not $engine){throw '沒有可用的 Windows OCR 語言，請安裝英文文字辨識元件後重啟。'}
    foreach($item in $request.Items) {
        $stream=$null;$bitmap=$null
        try {
            if($item.Pixels -lt 3){$texts[$item.Name]='未找到符合顏色的筆畫';continue}
            $file=Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($item.Path)) ([Windows.Storage.StorageFile])
            $stream=Await ($file.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
            $decoder=Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
            $bitmap=Await ($decoder.GetSoftwareBitmapAsync([Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8,[Windows.Graphics.Imaging.BitmapAlphaMode]::Ignore)) ([Windows.Graphics.Imaging.SoftwareBitmap])
            $ocr=Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            $texts[$item.Name]=$ocr.Text
            $values[$item.Name]=Convert-ColorNumber $ocr.Text
        }catch{$errors.Add($item.Name+'：'+$_.Exception.GetBaseException().Message)}
        finally{if($bitmap){$bitmap.Dispose()};if($stream){$stream.Dispose()}}
    }
}catch{$errors.Add($_.Exception.GetBaseException().Message)}
finally{foreach($p in $paths){Remove-Item -LiteralPath $p -ErrorAction SilentlyContinue}}
[pscustomobject]@{Values=[pscustomobject]$values;Texts=[pscustomobject]$texts;Error=($errors -join '；')}
