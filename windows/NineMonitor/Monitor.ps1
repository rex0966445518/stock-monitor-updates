param([switch]$SmokeTest)
#requires -Version 5.1
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Native.cs') -Raw -Encoding UTF8) -ReferencedAssemblies 'System.Windows.Forms.dll','System.Drawing.dll'
[NineMonitor.Desktop]::EnableDpiAwareness()
[Windows.Forms.Application]::EnableVisualStyles()
Import-Module (Join-Path $PSScriptRoot 'Core.psm1') -Force
$script:version=[version](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version.json') -Raw -Encoding UTF8|ConvertFrom-Json).version
$script:root=$PSScriptRoot
. (Join-Path $script:root 'LowSearch.ps1')
$script:searchMode=$false;$script:searchPause=$false
$script:searchRanges=[pscustomobject]@{Orange=[pscustomobject]@{Min=19.0;Max=21.0};Pink=[pscustomobject]@{Min=10.0;Max=12.0};White=[pscustomobject]@{Min=-20.0;Max=0.0}}
$script:names=@('Orange','Pink','White');$script:labels=@{Orange='橘色';Pink='粉紅／紫色';White='白色'}
$script:colors=@{Orange=[Drawing.Color]::Orange;Pink=[Drawing.Color]::HotPink;White=[Drawing.Color]::White}
$created=$false;$script:mutex=[Threading.Mutex]::new($true,'Local\NineMonitor-ThreeColor',[ref]$created)
if(-not $created){[void][Windows.Forms.MessageBox]::Show('三色數值監視器已開啟，請查看右下角圖示。');$mutex.Dispose();return}
$script:dataRoot=Join-Path $env:LOCALAPPDATA 'NineMonitor\Data'
[void][IO.Directory]::CreateDirectory($dataRoot)
$script:configPath=Join-Path $dataRoot 'threecolor-settings.json'
$script:channels=@{};foreach($n in $names){$channels[$n]=@{Region=$null;Rgb=-1;Threshold=80.0}}
$script:signature='';$script:active=$false;$script:job=$null;$script:updateJob=$null;$script:generation=0
$script:latch=@{Active=$false};$script:previous=$null;$script:borders=@();$script:clock=[Diagnostics.Stopwatch]::StartNew();$script:nextAt=0.0;$script:captureKeys=0L
$script:hotkey=New-Object NineMonitor.EscapeKey
$script:rightInput=New-Object NineMonitor.WindowsRightInput
$script:rightPump=New-Object NineMonitor.AutoRightPump($script:rightInput)
$script:autoMode=$false;$script:autoStarted=0.0;$script:lastSent=0L
$script:worker=Get-Content -LiteralPath (Join-Path $root 'OcrWorker.ps1') -Raw -Encoding UTF8
$script:voice=$null;$script:voiceChinese=$false;$script:voices=@()
try{Add-Type -AssemblyName System.Speech;$script:voice=New-Object Speech.Synthesis.SpeechSynthesizer;$script:voices=@($voice.GetInstalledVoices()|Where-Object{$_.Enabled});$zh=$voices|Where-Object{$_.VoiceInfo.Culture.Name -like 'zh*'}|Select-Object -First 1;if($zh){$voice.SelectVoice($zh.VoiceInfo.Name);$script:voiceChinese=$true}}catch{}
$bg=[Drawing.Color]::FromArgb(15,22,34);$fg=[Drawing.Color]::FromArgb(235,241,248);$card=[Drawing.Color]::FromArgb(25,36,52);$accent=[Drawing.Color]::FromArgb(73,225,180)
$script:ui=@{};$script:form=New-Object Windows.Forms.Form
$form.Text='三色數值監視器 v'+$version+' — 作者雷允澤';$work=[Windows.Forms.Screen]::PrimaryScreen.WorkingArea;$form.Size=New-Object Drawing.Size([Math]::Min(1110,[Math]::Max(900,$work.Width-40)),[Math]::Min(900,[Math]::Max(680,$work.Height-40)));$form.MinimumSize=New-Object Drawing.Size(900,680);$form.AutoScrollMinSize=New-Object Drawing.Size(1065,850);$form.StartPosition='CenterScreen';$form.BackColor=$bg;$form.ForeColor=$fg;$form.Font=New-Object Drawing.Font('Microsoft JhengHei',10);$form.AutoScroll=$true
function New-Label([string]$text,[int]$x,[int]$y,[int]$w,[int]$h=28){$c=New-Object Windows.Forms.Label;$c.Text=$text;$c.SetBounds($x,$y,$w,$h);$c.ForeColor=$fg;$form.Controls.Add($c);return $c}
function New-Button([string]$text,[int]$x,[int]$y,[int]$w=130){$c=New-Object Windows.Forms.Button;$c.Text=$text;$c.SetBounds($x,$y,$w,35);$c.FlatStyle='Flat';$c.BackColor=$card;$c.ForeColor=$fg;$form.Controls.Add($c);return $c}
$ui.title=New-Label '三色數值監視器' 24 18 600 44;$ui.title.Font=New-Object Drawing.Font('Microsoft JhengHei',25,[Drawing.FontStyle]::Bold)
$ui.auto=New-Button '自動右移 5 次／秒' 670 25 215
$ui.update=New-Button '一鍵更新' 900 25 150
$ui.sub=New-Label '先框選三項；搜索低點時設定範圍，再點圖表起始位置。ESC 停止。' 28 73 620 30
$ui.low=New-Button '搜索低點' 670 73 215
$ui.headers=New-Label '監視數值　　　　　　　　　　　警報門檻（嚴格大於）　　　　框選／顏色校正' 28 115 1010 30
$index=0
foreach($n in $names){
    $y=153+$index*86
    $u=@{};$u.value=New-Label ($labels[$n]+'　—') 24 $y 310 57;$u.value.Font=New-Object Drawing.Font('Microsoft JhengHei',24,[Drawing.FontStyle]::Bold);$u.value.ForeColor=$colors[$n];$u.value.BackColor=$card
    $u.threshold=New-Object Windows.Forms.NumericUpDown;$u.threshold.SetBounds(364,($y+9),128,30);$u.threshold.Minimum=-1000000;$u.threshold.Maximum=1000000;$u.threshold.DecimalPlaces=2;$u.threshold.Value=80;$form.Controls.Add($u.threshold)
    $u.select=New-Button ('框選'+$labels[$n]) 530 ($y+5) 135;$u.select.Tag=$n
    $u.pick=New-Button '點取筆畫顏色' 680 ($y+5) 150;$u.pick.Tag=$n
    $u.reset=New-Button '預設顏色' 845 ($y+5) 120;$u.reset.Tag=$n
    $u.swatch=New-Label '' 984 ($y+8) 36 28;$u.swatch.BackColor=$colors[$n]
    $u.region=New-Label '尚未框選，請只框一個數字' 364 ($y+46) 670 26
    $ui[$n]=$u;$index++
}
$ui.test=New-Button '單次辨識測試' 24 430 170;$ui.start=New-Button '開始監視' 210 430 150;$ui.start.ForeColor=$accent
$ui.stop=New-Button '停止（ESC）' 376 430 150;$ui.say=New-Button '測試語音' 542 430 135;$ui.demo=New-Button '開啟測試數字' 693 430 160
$ui.intervalLabel=New-Label '取樣／記錄間隔（秒）' 28 486 235
$ui.interval=New-Object Windows.Forms.NumericUpDown;$ui.interval.SetBounds(270,482,85,28);$ui.interval.Minimum=5;$ui.interval.Maximum=3600;$ui.interval.Value=60;$form.Controls.Add($ui.interval)
$ui.minimize=New-Object Windows.Forms.CheckBox;$ui.minimize.SetBounds(390,482,230,28);$ui.minimize.Text='開始後最小化';$ui.minimize.Checked=$true;$ui.minimize.ForeColor=$fg;$form.Controls.Add($ui.minimize)
$ui.voiceLabel=New-Label '語音' 630 486 60
$ui.voice=New-Object Windows.Forms.ComboBox;$ui.voice.DropDownStyle='DropDownList';$ui.voice.SetBounds(690,482,360,30);$form.Controls.Add($ui.voice)
foreach($v in $voices){[void]$ui.voice.Items.Add($v.VoiceInfo.Name)}
if($voice -and $ui.voice.Items.Count -gt 0){$ui.voice.SelectedItem=$voice.Voice.Name}else{$ui.voice.Enabled=$false}
$ui.status=New-Label '請分別框選三個數字，再進行單次辨識測試。' 24 530 1028 40;$ui.status.BackColor=$card;$ui.status.ForeColor=$accent
$ui.last=New-Label '自動右移時每輪 OCR 完成即取下一筆；不保證每次右移都有紀錄。三項共同超過門檻才報警。' 28 580 1020 27
$ui.raw=New-Object Windows.Forms.TextBox;$ui.raw.SetBounds(24,618,1028,65);$ui.raw.Multiline=$true;$ui.raw.ReadOnly=$true;$ui.raw.ScrollBars='Vertical';$ui.raw.BackColor=$card;$ui.raw.ForeColor=$fg;$form.Controls.Add($ui.raw)
$ui.history=New-Object Windows.Forms.ListView;$ui.history.SetBounds(24,695,1028,100);$ui.history.View='Details';$ui.history.FullRowSelect=$true;$ui.history.GridLines=$true;$ui.history.BackColor=$card;$ui.history.ForeColor=$fg
foreach($col in @(@('時間',175),@('橘色',85),@('粉紅色',85),@('白色',85),@('共同條件',100),@('狀態',480))){[void]$ui.history.Columns.Add([string]$col[0],[int]$col[1])};$form.Controls.Add($ui.history)
$ui.export=New-Button '匯出 CSV' 24 810 135;$ui.folder=New-Button '開啟紀錄資料夾' 175 810 180
$ui.footer=New-Label ('v'+$version+'　｜　本機辨識　｜　三項條件採用 AND　｜　作者雷允澤') 380 817 660 30
$script:tray=New-Object Windows.Forms.NotifyIcon;$tray.Icon=[Drawing.SystemIcons]::Information;$tray.Text='三色數值監視器';$tray.Visible=$true
$menu=New-Object Windows.Forms.ContextMenuStrip;$miShow=$menu.Items.Add('顯示監視器');$miStop=$menu.Items.Add('停止監視');$miExit=$menu.Items.Add('結束');$tray.ContextMenuStrip=$menu
function Status([string]$text,[bool]$bad=$false){$ui.status.Text=$text;$ui.status.ForeColor=if($bad){[Drawing.Color]::Orange}else{$accent}}
function Fmt($n){if($null -eq $n){return '—'};return ([double]$n).ToString('0.00',[Globalization.CultureInfo]::InvariantCulture)}
function Show-App{$form.Show();$form.WindowState='Normal';$form.Activate()}
function Update-RegionLabels{
    foreach($n in $names){$c=$channels[$n];if($c.Region){$r=$c.Region;$ui[$n].region.Text='X='+$r.X+' Y='+$r.Y+' 寬='+$r.Width+' 高='+$r.Height}else{$ui[$n].region.Text='尚未框選，請只框一個數字'};$ui[$n].swatch.BackColor=if($c.Rgb -ge 0){[Drawing.Color]::FromArgb(255,(($c.Rgb -shr 16) -band 255),(($c.Rgb -shr 8) -band 255),($c.Rgb -band 255))}else{$colors[$n]}}
}
function Save-Settings{
    $c=[ordered]@{};foreach($n in $names){$r=$channels[$n].Region;$c[$n]=@{Region=$(if($r){@{X=$r.X;Y=$r.Y;Width=$r.Width;Height=$r.Height}}else{$null});Rgb=$channels[$n].Rgb;Threshold=[double]$ui[$n].threshold.Value}}
    $prefs=@{Schema=2;Channels=$c;Signature=$signature;Interval=[int]$ui.interval.Value;Minimize=$ui.minimize.Checked;Voice=[string]$ui.voice.SelectedItem;SearchRanges=$script:searchRanges;PauseOnSearchHit=$script:searchPause}
    $tmp=$configPath+'.tmp';[IO.File]::WriteAllText($tmp,($prefs|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($true)))
    if(Test-Path $configPath){[IO.File]::Replace($tmp,$configPath,$null)}else{[IO.File]::Move($tmp,$configPath)}
}
function Controls{
    $idle=-not $script:active -and $null -eq $script:job -and $null -eq $script:updateJob
    $ready=$true;foreach($n in $names){if(-not $channels[$n].Region){$ready=$false};foreach($key in @('select','pick','reset','threshold')){$ui[$n][$key].Enabled=$idle}}
    $ui.low.Enabled=$idle -and $ready;$ui.auto.Enabled=$idle -and $ready;$ui.test.Enabled=$idle -and $ready;$ui.start.Enabled=$idle -and $ready;$ui.stop.Enabled=$active
    $ui.update.Enabled=$null -eq $job -and $null -eq $updateJob;$ui.interval.Enabled=$idle;$ui.minimize.Enabled=$idle;$ui.voice.Enabled=$idle -and $voices.Count -gt 0
}
function Stop-Monitor{
    $rightPump.Stop();$script:lastSent=$rightPump.SentCount;$script:autoMode=$false
    $script:active=$false;$script:generation++;$hotkey.Unregister()
    foreach($b in $script:borders){$b.Close();$b.Dispose()};$script:borders=@()
    if($voice){$voice.SpeakAsyncCancelAll()};[void][NineMonitor.Desktop]::SetThreadExecutionState(2147483648)
    Controls;Status '已停止；未完成的辨識結果會捨棄，不會補報。';$tray.Text='三色監視器：已停止'
}
function Speak-Alert($values,[bool]$test=$false){
    $zh=$script:voiceChinese
    if($test){$text=if($zh){'這是三色數值監視器的語音測試。三個數值同時超過門檻時，會提醒您。'}else{'This is a voice test. An alert sounds when all three values exceed their thresholds.'}}
    else{
        if($script:searchMode){$text=if($zh){'搜索低點，發現三項同時符合搜尋範圍。橘色 '+(Fmt $values.Orange)+'，第二色 '+(Fmt $values.Pink)+'，白色 '+(Fmt $values.White)}else{'Low-point search match. All three values are within their ranges. Orange '+(Fmt $values.Orange)+', second color '+(Fmt $values.Pink)+', white '+(Fmt $values.White)}}
        else{$text=if($zh){'注意，三個數值都已超過門檻。橘色 '+(Fmt $values.Orange)+'，第二色 '+(Fmt $values.Pink)+'，白色 '+(Fmt $values.White)}else{'All three values exceeded their thresholds. Orange '+(Fmt $values.Orange)+', second color '+(Fmt $values.Pink)+', white '+(Fmt $values.White)}}
    }
    [Media.SystemSounds]::Exclamation.Play();$tray.BalloonTipTitle='三色數值條件達成';$tray.BalloonTipText=$text;$tray.ShowBalloonTip(8000)
    if($voice -and $voices.Count -gt 0){$voice.SpeakAsyncCancelAll();[void]$voice.SpeakAsync($text)}else{Status '找不到語音引擎，目前只有音效與通知。請在 Windows 安裝語音元件後重啟。' $true}
}
function Add-Row($r){$condition=if($r.PSObject.Properties['CommonMatched']){[string]$r.CommonMatched}else{[string]$r.AllAbove};$item=New-Object Windows.Forms.ListViewItem([string]$r.Time);foreach($s in @((Fmt $r.Orange),(Fmt $r.Pink),(Fmt $r.White),$condition,[string]$r.Status)){[void]$item.SubItems.Add([string]$s)};[void]$ui.history.Items.Insert(0,$item);while($ui.history.Items.Count -gt 300){$ui.history.Items.RemoveAt($ui.history.Items.Count-1)}}
function Record($values,[string]$note,[datetimeoffset]$stamp,[bool]$test){
    foreach($n in $names){$ui[$n].value.Text=$labels[$n]+'　'+(Fmt $values.$n)}
    $ui.last.Text=$stamp.ToString('yyyy-MM-dd HH:mm:ss.fff zzz')+$(if($test){'　單次測試，不寫入正式紀錄、不觸發門檻警報'}else{'　同一次畫面取樣'})
    if($test){Status ('測試結果：'+$note) ($null -eq $values.Orange -or $null -eq $values.Pink -or $null -eq $values.White);return}
    $thresholds=[pscustomobject]@{Orange=[double]$ui.Orange.threshold.Value;Pink=[double]$ui.Pink.threshold.Value;White=[double]$ui.White.threshold.Value}
    $complete=$true;$all=$true
    foreach($n in $names){
        if($null -eq $values.$n){$complete=$false;$all=$false}
        elseif($script:searchMode){if($values.$n -lt $script:searchRanges.$n.Min -or $values.$n -gt $script:searchRanges.$n.Max){$all=$false}}
        elseif($values.$n -le $thresholds.$n){$all=$false}
    }
    $fire=if($script:searchMode){Test-ThreeRanges $latch $values $script:searchRanges}else{Test-AllThree $latch $values $thresholds}
    if($fire){$note+='；語音警報'}
    $row=[pscustomobject]@{Time=$stamp.ToString('yyyy-MM-dd HH:mm:ss.fff zzz');Orange=$values.Orange;Pink=$values.Pink;White=$values.White;OrangeThreshold=$thresholds.Orange;PinkThreshold=$thresholds.Pink;WhiteThreshold=$thresholds.White;AllAbove=$(if(-not $script:searchMode -and $complete){[string]$all}else{$null});AllInRange=$(if($script:searchMode -and $complete){[string]$all}else{$null});CommonMatched=$(if($complete){[string]$all}else{'未知'});SearchRanges=$(if($script:searchMode){$script:searchRanges|ConvertTo-Json -Depth 4 -Compress}else{''});Status=$note;Mode=$(if($script:searchMode){'LowSearch5Hz'}elseif($script:autoMode){'AutoRight5Hz'}else{'Timed'});RightPresses=$script:captureKeys;SampleId=[guid]::NewGuid().ToString('N')}
    try{[IO.File]::AppendAllText((Join-Path $dataRoot ($stamp.ToString('yyyy-MM-dd')+'-threecolor.jsonl')),($row|ConvertTo-Json -Compress)+[Environment]::NewLine,(New-Object Text.UTF8Encoding($true)));Add-Row $row;Status ('已記錄：'+$note) (-not $complete)}catch{Status ('紀錄寫入失敗：'+$_.Exception.Message) $true}
    if($fire){if($script:searchMode -and $script:searchPause){Stop-Monitor;Status ('搜索命中，已暫停；命中取樣的右移次數：'+$script:captureKeys)};Speak-Alert $values}
}
function Empty-Values{[pscustomobject]@{Orange=$null;Pink=$null;White=$null}}
function Begin-Sample([bool]$test=$false){
    if($script:job){return}
    if((Get-ScreenSignature) -ne $script:signature){Stop-Monitor;Status '螢幕配置或解析度已改變，請重新框選三項。' $true;return}
    $stamp=[datetimeoffset]::Now;$items=@();$shot=$null
    $sampleKeys=if($script:autoMode){$rightPump.SentCount}else{0L};$script:captureKeys=$sampleKeys
    try{
        $union=$channels.Orange.Region
        foreach($n in $names){$r=$channels[$n].Region;if(-not $r -or -not [Windows.Forms.SystemInformation]::VirtualScreen.Contains($r)){throw '選框無效或超出螢幕，請重新框選。'};$union=[Drawing.Rectangle]::Union($union,$r)}
        if(-not $test -and $form.Visible -and $form.WindowState -ne 'Minimized'){
            foreach($n in $names){if($form.Bounds.IntersectsWith($channels[$n].Region)){Record (Empty-Values) '監視器遮住數字，請移開或最小化' $stamp $false;$script:nextAt=$(if($script:autoMode){$clock.Elapsed.TotalSeconds}else{$clock.Elapsed.TotalSeconds+[int]$ui.interval.Value});return}}
        }
        # One CopyFromScreen snapshot covers all three boxes at the same instant.
        if($script:autoMode -and (-not $rightPump.Running -or -not $rightPump.Armed)){return}
        $shot=[NineMonitor.Desktop]::Capture($union)
        foreach($n in $names){
            $r=$channels[$n].Region;$local=New-Object Drawing.Rectangle(($r.X-$union.X),($r.Y-$union.Y),$r.Width,$r.Height)
            $pixels=0;$masked=[NineMonitor.ColorFilter]::Mask($shot,$local,$n,[int]$channels[$n].Rgb,[ref]$pixels)
            $path=Join-Path ([IO.Path]::GetTempPath()) ('NineMonitor-'+[guid]::NewGuid().ToString('N')+'.png')
            try{$masked.Save($path,[Drawing.Imaging.ImageFormat]::Png)}finally{$masked.Dispose()}
            $items+=@{Name=$n;Path=$path;Pixels=$pixels}
        }
        $request=@{Core=(Join-Path $root 'Core.psm1');Items=$items}|ConvertTo-Json -Depth 5 -Compress
        $ps=[PowerShell]::Create();[void]$ps.AddScript($worker).AddArgument($request)
        $handle=$ps.BeginInvoke();$script:job=@{PS=$ps;Handle=$handle;Items=$items;Stamp=$stamp;Test=$test;Generation=$generation;Keys=$sampleKeys;Auto=$script:autoMode;KeyStart=$sampleKeys}
        Status '正在以同一次畫面辨識三色數值…';Controls
    }catch{foreach($i in $items){Remove-Item -LiteralPath $i.Path -ErrorAction SilentlyContinue};Record (Empty-Values) ('擷取失敗：'+$_.Exception.Message) $stamp $test}
    finally{if($shot){$shot.Dispose()};$script:nextAt=$(if($script:autoMode){$clock.Elapsed.TotalSeconds}else{$clock.Elapsed.TotalSeconds+[int]$ui.interval.Value})}
}
function Poll-Ocr{
    if(-not $job -or -not $job.Handle.IsCompleted){return}
    $j=$job;$script:job=$null
    try{
        $out=@($j.PS.EndInvoke($j.Handle));$r=$out|Where-Object{$_.PSObject.Properties['Values']}|Select-Object -Last 1
        if($j.Generation -ne $generation){return};if(-not $r){throw ('沒有 OCR 結果：'+($j.PS.Streams.Error|Out-String))}
        $ui.raw.Text='橘色 OCR：'+$r.Texts.Orange+'　｜　粉紅 OCR：'+$r.Texts.Pink+'　｜　白色 OCR：'+$r.Texts.White
        $note=if($r.Error){$r.Error}elseif($null -ne $r.Values.Orange -and $null -ne $r.Values.Pink -and $null -ne $r.Values.White){'三項辨識完成'}else{'部分缺值或有多個數字，請縮小選框／校正顏色'}
        $script:captureKeys=$j.Keys
        if($j.Auto){$note+='；取樣時已送出 '+$j.Keys+' 次右鍵'}
        Record $r.Values $note $j.Stamp $j.Test
    }catch{if($j.Generation -eq $generation){Record (Empty-Values) ('辨識失敗：'+$_.Exception.Message) $j.Stamp $j.Test}}
    finally{$j.PS.Dispose();foreach($i in $j.Items){Remove-Item -LiteralPath $i.Path -ErrorAction SilentlyContinue};Controls}
}
foreach($n in $names){
    $ui[$n].select.Add_Click({
        $n=[string]$this.Tag
        try{$form.Hide();[Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 200;$selector=New-Object NineMonitor.Selector;$selector.Prompt='請框住'+$labels[$n]+'的一個數字；放開確認，ESC 取消'
            try{if($selector.ShowDialog() -eq 'OK'){$newSignature=Get-ScreenSignature;if($signature -and $signature -ne $newSignature){foreach($key in $names){$channels[$key].Region=$null}};$channels[$n].Region=$selector.Selected;$script:signature=$newSignature;Update-RegionLabels;Save-Settings;Status ('已設定'+$labels[$n]+'區域')}}finally{$selector.Dispose()}
        }catch{Status $_.Exception.Message $true}finally{Show-App;Controls}
    })
    $ui[$n].pick.Add_Click({$n=[string]$this.Tag;try{$form.Hide();[Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 200;$picker=New-Object NineMonitor.PixelPicker;try{if($picker.ShowDialog() -eq 'OK'){$c=$picker.Picked;$channels[$n].Rgb=([int]$c.R * 65536)+([int]$c.G * 256)+[int]$c.B;Update-RegionLabels;Save-Settings;Status '顏色已校正，請單次測試辨識。'}}finally{$picker.Dispose()}}catch{Status $_.Exception.Message $true}finally{Show-App;Controls}})
    $ui[$n].reset.Add_Click({$channels[[string]$this.Tag].Rgb=-1;Update-RegionLabels;try{Save-Settings}catch{};Status '已恢復預設顏色範圍。'})
}
$ui.test.Add_Click({try{$form.Hide();[Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 200;Begin-Sample $true}finally{Show-App}})
$ui.start.Add_Click({try{$script:searchMode=$false;$script:autoMode=$false;Save-Settings;$script:active=$true;$script:generation++;$script:latch=@{Active=$false};$script:nextAt=$clock.Elapsed.TotalSeconds+1
    foreach($n in $names){$b=New-Object NineMonitor.Border($channels[$n].Region);$b.Show();$script:borders+=,$b};[void][NineMonitor.Desktop]::SetThreadExecutionState(2147483651)
    $ok=$hotkey.Register();Controls;if($ui.minimize.Checked){$form.WindowState='Minimized'};Status $(if($ok){'監視中，三項同時嚴格超過門檻才報警。ESC 可停止。'}else{'監視中，ESC 被占用，請使用停止按鈕。'}) (-not $ok);$tray.Text='三色監視器：監視中'
}catch{Stop-Monitor;Status $_.Exception.Message $true}})
$ui.auto.Add_Click({
    try{
        Save-Settings
        if((Get-ScreenSignature) -ne $signature){throw '螢幕配置已變更，請重新框選。'}
        if(-not $hotkey.Register()){throw 'ESC 停止鍵被其他程式占用，請關閉衝突後重試。'}
        $script:searchMode=$false;$script:autoMode=$true;$script:active=$true;$script:generation++;$script:latch=@{Active=$false};$script:autoStarted=$clock.Elapsed.TotalSeconds
        $script:nextAt=$clock.Elapsed.TotalSeconds+3.0
        foreach($n in $names){$b=New-Object NineMonitor.Border($channels[$n].Region);$b.Show();$script:borders+=,$b}
        [void][NineMonitor.Desktop]::SetThreadExecutionState(2147483651)
        Controls;$form.WindowState='Minimized'
        $tray.BalloonTipTitle='3 秒內點選目標圖表';$tray.BalloonTipText='之後每秒右移 5 次，持續辨識並記錄三色。ESC／切換視窗停止。';$tray.ShowBalloonTip(3000)
        $rightInput.ClearSelection();$rightPump.Start(3000);Status '3 秒內點選目標圖表。目標將被固定，切換視窗即停止。'
    }catch{Stop-Monitor;Status $_.Exception.Message $true}
})
$ui.low.Add_Click({
    $dialog=$null
    try{
        $dialog=New-LowSearchDialog $script:searchRanges $script:searchPause
        if($dialog.ShowDialog($form) -ne 'OK'){return}
        $script:searchRanges=$dialog.Tag.Selected;$script:searchPause=$dialog.Tag.Pause.Checked
        $dialog.Dispose();$dialog=$null
        Start-LowSearch
    }catch{Status $_.Exception.Message $true}finally{if($dialog){$dialog.Dispose()}}
})
$ui.stop.Add_Click({Stop-Monitor});$hotkey.Add_Pressed({Stop-Monitor});$miStop.Add_Click({Stop-Monitor});$miShow.Add_Click({Show-App});$tray.Add_DoubleClick({Show-App});$miExit.Add_Click({$form.Close()})
$ui.say.Add_Click({Speak-Alert (Empty-Values) $true})
$ui.voice.Add_SelectedIndexChanged({try{$voice.SelectVoice([string]$ui.voice.SelectedItem);$script:voiceChinese=$voice.Voice.Culture.Name -like 'zh*'}catch{Status '所選語音無法使用。' $true}})
$ui.demo.Add_Click({$d=New-Object Windows.Forms.Form;$d.Text='測試數字（請將這個視窗維持可見）';$d.BackColor=[Drawing.Color]::Black;$d.Size=New-Object Drawing.Size(440,320);$d.StartPosition='CenterScreen';$i=0;foreach($n in $names){$box=New-Object Windows.Forms.NumericUpDown;$box.SetBounds(45,(20+$i*80),320,65);$box.Minimum=0;$box.Maximum=1000;$box.DecimalPlaces=2;$box.Value=79;$box.Font=New-Object Drawing.Font('Segoe UI',28,[Drawing.FontStyle]::Bold);$box.ForeColor=$colors[$n];$box.BackColor=[Drawing.Color]::Black;$d.Controls.Add($box);$i++};$d.Show()})
$ui.folder.Add_Click({Start-Process explorer.exe -ArgumentList ('"'+$dataRoot+'"')})
$ui.export.Add_Click({$d=New-Object Windows.Forms.SaveFileDialog;$d.Filter='CSV (*.csv)|*.csv';$d.FileName='ThreeColor-'+[datetime]::Now.ToString('yyyyMMdd-HHmmss')+'.csv';try{if($d.ShowDialog() -ne 'OK'){return};$records=@(Get-ChildItem -LiteralPath $dataRoot -Filter '*-threecolor.jsonl'|Sort-Object Name|ForEach-Object{Get-Content -LiteralPath $_.FullName -Encoding UTF8|ForEach-Object{try{$_|ConvertFrom-Json}catch{}}});if($records.Count -eq 0){Status '尚無可匯出的紀錄。' $true;return};$records|Export-Csv -LiteralPath $d.FileName -NoTypeInformation -Encoding UTF8;Status ('已匯出 '+$records.Count+' 筆')}catch{Status $_.Exception.Message $true}finally{$d.Dispose()}})
$ui.update.Add_Click({if($active){Stop-Monitor};$ps=[PowerShell]::Create();[void]$ps.AddScript((Get-Content -LiteralPath (Join-Path $root 'UpdateWorker.ps1') -Raw -Encoding UTF8)).AddArgument($version.ToString()).AddArgument((Join-Path $root 'Core.psm1'));$script:updateJob=@{PS=$ps;Handle=$ps.BeginInvoke()};Controls;Status '正在檢查專屬更新通道…'})
function Poll-Update{
    if(-not $updateJob -or -not $updateJob.Handle.IsCompleted){return};$j=$updateJob;$script:updateJob=$null
    try{$out=@($j.PS.EndInvoke($j.Handle));$r=$out|Where-Object{$_.PSObject.Properties['State']}|Select-Object -Last 1;if(-not $r){throw '更新程序沒有回傳結果。'}
        if($r.State -eq 'Ready'){Status ('新版本 '+$r.Version+' 已下載並通過 SHA-256 校驗，正在安裝…');Start-Process -FilePath $r.Path -ArgumentList ('/UPDATE /WAITPID='+$PID);$form.Close()}
        elseif($r.State -eq 'Current'){Status ('目前 v'+$version+' 已是最新版本。')}else{Status ('更新失敗：'+$r.Error) $true}
    }catch{Status $_.Exception.Message $true}finally{$j.PS.Dispose();Controls}
}
try{
    if(Test-Path $configPath){$p=Get-Content -LiteralPath $configPath -Raw -Encoding UTF8|ConvertFrom-Json;$script:signature=[string]$p.Signature
        foreach($n in $names){$c=$p.Channels.$n;$channels[$n].Rgb=[int]$c.Rgb;$ui[$n].threshold.Value=[decimal]$c.Threshold;if($c.Region -and $signature -eq (Get-ScreenSignature) -and $c.Region.Width -ge 30 -and $c.Region.Height -ge 20){$channels[$n].Region=New-Object Drawing.Rectangle([int]$c.Region.X,[int]$c.Region.Y,[int]$c.Region.Width,[int]$c.Region.Height)}}
        if($p.PSObject.Properties['SearchRanges']){Assert-RangeSettings $p.SearchRanges;$script:searchRanges=$p.SearchRanges;$script:searchPause=[bool]$p.PauseOnSearchHit}
        $ui.interval.Value=[decimal]$p.Interval;$ui.minimize.Checked=[bool]$p.Minimize;if($p.Voice -and $ui.voice.Items.Contains([string]$p.Voice)){$ui.voice.SelectedItem=[string]$p.Voice}
    }
}catch{Status '先前設定無法完整還原，請重新設定三項。' $true}
Update-RegionLabels;Controls
$timer=New-Object Windows.Forms.Timer;$timer.Interval=200
$timer.Add_Tick({try{
    if($script:autoMode -and $script:active -and -not $rightPump.Running){
        $why=$rightPump.StopReason;$count=$rightPump.SentCount;Stop-Monitor
        $explanation=switch($why){
            'focus' {'目標視窗已切換、關閉或最小化'}
            'escape' {'已按 ESC'}
            'modifier' {'偵測到 Ctrl／Shift／Alt／Win 按鍵'}
            'input' {'Windows 無法送出按鍵，請檢查目標程式權限'}
            'target' {'未選到可操作的目標圖表'}
            default {'自動右移已停止'}
        }
        Status ($explanation+'；本次共送出 '+$count+' 次右鍵。') $true
    }
    Poll-Ocr;Poll-Update
    if($script:autoMode -and $script:active){
        if($rightPump.Armed){$tray.Text=$(if($script:searchMode){'搜索低點：'}else{'自動右移：'})+'已送出 '+$rightPump.SentCount+' 次'}
        elseif($script:searchMode){Status '正在啟動所選圖表的低點搜索…'}
        else{Status ('請點目標圖表，倒數 '+[Math]::Max(0,[Math]::Ceiling(3-($clock.Elapsed.TotalSeconds-$autoStarted)))+' 秒')}
    }
    if($active -and -not $job -and $clock.Elapsed.TotalSeconds -ge $nextAt -and (-not $script:autoMode -or $rightPump.Armed)){Begin-Sample $false}
}catch{Stop-Monitor;Status ('執行錯誤：'+$_.Exception.Message) $true}})
$form.Add_FormClosing({Stop-Monitor;try{Save-Settings}catch{};$timer.Stop()})
if($SmokeTest){$smoke=New-Object Windows.Forms.Timer;$smoke.Interval=1600;$smoke.Add_Tick({$smoke.Stop();$form.Close()});$smoke.Start()}
$timer.Start()
try{[Windows.Forms.Application]::Run($form)}finally{
    $timer.Dispose();$rightPump.Dispose();$hotkey.Dispose();if($job){try{$job.PS.Stop();$job.PS.Dispose()}catch{};foreach($i in $job.Items){Remove-Item -LiteralPath $i.Path -ErrorAction SilentlyContinue}}
    if($updateJob){try{$updateJob.PS.Stop();$updateJob.PS.Dispose()}catch{}};if($voice){$voice.Dispose()};$tray.Visible=$false;$tray.Dispose();$menu.Dispose();$form.Dispose();$mutex.ReleaseMutex();$mutex.Dispose()
}
