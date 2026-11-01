function New-LowSearchDialog($Ranges,[bool]$PauseOnHit=$false) {
    Assert-RangeSettings $Ranges
    $d=New-Object Windows.Forms.Form
    $d.Text='搜索低點 — 三個範圍同時滿足';$d.Size=New-Object Drawing.Size(650,370);$d.FormBorderStyle='FixedDialog';$d.MaximizeBox=$false;$d.MinimizeBox=$false;$d.StartPosition='CenterParent';$d.Font=New-Object Drawing.Font('Microsoft JhengHei',10)
    $title=New-Object Windows.Forms.Label;$title.Text='設定三項搜尋範圍（包含上下限），再點選圖表起始位置。';$title.SetBounds(20,18,600,30);$d.Controls.Add($title)
    $inputs=@{};$i=0;$captions=@{Orange='橘色數字';Pink='第二色（紫／粉紅）';White='白色數字'}
    foreach($name in @('Orange','Pink','White')) {
        $label=New-Object Windows.Forms.Label;$label.Text=$captions[$name];$label.SetBounds(20,(65+$i*55),190,28);$d.Controls.Add($label)
        $lo=New-Object Windows.Forms.NumericUpDown;$hi=New-Object Windows.Forms.NumericUpDown
        foreach($n in @($lo,$hi)){$n.Minimum=-1000000;$n.Maximum=1000000;$n.DecimalPlaces=2;$n.Width=135;$d.Controls.Add($n)}
        $lo.SetBounds(220,(62+$i*55),135,28);$hi.SetBounds(425,(62+$i*55),135,28);$lo.Value=[decimal]$Ranges.$name.Min;$hi.Value=[decimal]$Ranges.$name.Max
        $dash=New-Object Windows.Forms.Label;$dash.Text='～';$dash.SetBounds(378,(65+$i*55),30,28);$d.Controls.Add($dash)
        $inputs[$name]=@{Min=$lo;Max=$hi};$i++
    }
    $pause=New-Object Windows.Forms.CheckBox;$pause.Text='找到符合條件時暫停右移（未勾選會繼續搜索）';$pause.Checked=$PauseOnHit;$pause.SetBounds(22,232,580,30);$d.Controls.Add($pause)
    $start=New-Object Windows.Forms.Button;$start.Text='開始搜索';$start.SetBounds(425,280,150,36);$d.Controls.Add($start)
    $cancel=New-Object Windows.Forms.Button;$cancel.Text='取消';$cancel.SetBounds(265,280,130,36);$cancel.DialogResult='Cancel';$d.Controls.Add($cancel);$d.CancelButton=$cancel
    $d.Tag=@{Inputs=$inputs;Pause=$pause;Selected=$null;Start=$start}
    $start.Add_Click({
        $f=$this.FindForm();$r=[ordered]@{}
        foreach($name in @('Orange','Pink','White')){$r[$name]=[pscustomobject]@{Min=[double]$f.Tag.Inputs[$name].Min.Value;Max=[double]$f.Tag.Inputs[$name].Max.Value}}
        try{Assert-RangeSettings ([pscustomobject]$r);$f.Tag.Selected=[pscustomobject]$r;$f.DialogResult='OK'}
        catch{[void][Windows.Forms.MessageBox]::Show($f,$_.Exception.Message,'範圍設定錯誤')}
    })
    return $d
}
function Start-LowSearch {
    $started=$false;$picker=$null
    try{
        if((Get-ScreenSignature) -ne $script:signature){throw '螢幕配置已改變，請重新框選三色區域。'}
        Assert-RangeSettings $script:searchRanges
        Save-Settings
        $form.Hide();[Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 200
        $picker=New-Object NineMonitor.TargetClickPicker
        if($picker.ShowDialog() -ne 'OK'){return}
        $point=$picker.SelectedPoint;$picker.Dispose();$picker=$null
        if(-not $hotkey.Register()){throw 'ESC 被其他程式占用，請先解除衝突再搜索。'}
        if(-not $rightInput.SelectPoint($point)){throw '無法選取該位置的目標視窗，請點圖表區域並確認程式權限相同。'}
        $script:searchMode=$true;$script:autoMode=$true;$script:active=$true;$script:generation++;$script:latch=@{Active=$false};$script:autoStarted=$clock.Elapsed.TotalSeconds
        $script:nextAt=$clock.Elapsed.TotalSeconds+0.2
        foreach($name in $names){$b=New-Object NineMonitor.Border($channels[$name].Region);$b.Show();$script:borders+=,$b}
        [void][NineMonitor.Desktop]::SetThreadExecutionState(2147483651)
        $rightPump.Start(200);Controls;Status '搜索低點中：三項同時落在設定範圍才語音提醒；ESC 停止。';$started=$true
    }catch{Stop-Monitor;Status $_.Exception.Message $true}
    finally{if($picker){$picker.Dispose()};if(-not $started){Show-App;Controls}}
}
