package com.rex.twboardingscanner.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import java.math.BigInteger
import java.time.LocalDate
import java.util.Locale

class BtRobotActivity:AppCompatActivity(){
    private lateinit var store:BtRobotStore
    private lateinit var status:TextView;private lateinit var counts:TextView;private lateinit var best:TextView;private lateinit var coverage:TextView
    private lateinit var startButton:com.google.android.material.button.MaterialButton
    private lateinit var pauseButton:com.google.android.material.button.MaterialButton
    private lateinit var bestButton:com.google.android.material.button.MaterialButton
    private lateinit var records:LinearLayout
    private lateinit var sectorButton:com.google.android.material.button.MaterialButton
    private lateinit var ruleSummary:TextView
    private val radarRuleButtons=mutableMapOf<RadarType,com.google.android.material.button.MaterialButton>()
    private lateinit var spaceLabel:TextView
    private lateinit var robotRange:TextView
    private lateinit var robotStartDate:com.google.android.material.button.MaterialButton
    private lateinit var robotEndDate:com.google.android.material.button.MaterialButton
    private lateinit var selectedDates:RobotBacktestDates
    private lateinit var robotCapital:EditText
    private lateinit var robotHoldings:EditText
    private lateinit var robotProfit:EditText
    private lateinit var paramNotice:TextView
    private lateinit var sweepCheck:CheckBox
    private lateinit var trendHoldCheck:CheckBox
    private var enabledRadars:Set<RadarType> = defaultActiveRadars()
    private var robotDConfig:DPatternConfig = DPatternConfig.DEFAULT
    private var robotExclusions=ExclusionConditions.empty()
    private val exclusionButtons=mutableMapOf<RadarType,com.google.android.material.button.MaterialButton>()
    private var robotEConfig:EPatternConfig = EPatternConfig.DEFAULT
    private var robotBConfig:BPatternConfig=BPatternConfig.DEFAULT
    private var robotFConfig:FPatternConfig = FPatternConfig.DEFAULT
    private val radarModeButtons=mutableMapOf<RadarType,com.google.android.material.button.MaterialButton>()
    private fun refreshRadarModes(){
        radarModeButtons.forEach{(type,button)->
            val lit=type in enabledRadars
            NeonUi.radarSwitch(button,type,lit)
        }
        exclusionButtons.forEach{(type,button)->button.text="${type.name.take(1)} 排除 ${robotExclusions.getValue(type).size}"}
        if(::paramNotice.isInitialized)refreshParameterHint()
    }
    private lateinit var sweepFields:LinearLayout
    private lateinit var hFrom:EditText;private lateinit var hTo:EditText
    private lateinit var pFrom:EditText;private lateinit var pTo:EditText
    private lateinit var sweepPreview:TextView
    private lateinit var topButton:com.google.android.material.button.MaterialButton
    private var fieldsSession=""
    private var busy=false;private var recovering=false;private var exportId=""
    private var stamp="";private var lastId="";private var page=0
    private var visible=false
    private var alert:androidx.appcompat.app.AlertDialog?=null
    private val handler=Handler(Looper.getMainLooper())
    private val poll=object:Runnable{override fun run(){render();recover();handler.postDelayed(this,2000)}}
    private fun label(s:String,size:Float=13f,color:Int=NeonUi.muted,bold:Boolean=false)=NeonUi.label(this,s,size,color,bold)
    private fun signed(v:Double)=String.format(Locale.TAIWAN,"%+,.0f",v)
    private fun num(v:Any)=java.text.DecimalFormat("#,###").format(v)
    private fun color(v:Double)=if(v>=0)NeonUi.pink else NeonUi.mint
    private fun panel(accent:Int)=NeonUi.vertical(this).apply{background=NeonUi.panel(this@BtRobotActivity,accent);setPadding(NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12),NeonUi.dp(context,12))}
    private fun paramBox(title:String,hint:String,accent:Int,code:String,decimal:Boolean):Pair<LinearLayout,EditText>{
        val field=EditText(this).apply{
            tag=code
            inputType=InputType.TYPE_CLASS_NUMBER or (if(decimal)InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            textSize=17f;setTextColor(NeonUi.ink);setHintTextColor(NeonUi.muted)
            gravity=Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setSingleLine(true);setSelectAllOnFocus(true)
            background=NeonUi.panel(this@BtRobotActivity,accent)
            setPadding(NeonUi.dp(context,3),NeonUi.dp(context,6),NeonUi.dp(context,3),NeonUi.dp(context,6))
            minHeight=NeonUi.dp(context,48)
            contentDescription="$title，可自行輸入"
        }
        val card=NeonUi.vertical(this).apply{
            background=NeonUi.panel(this@BtRobotActivity,accent)
            setPadding(NeonUi.dp(context,6),NeonUi.dp(context,9),NeonUi.dp(context,6),NeonUi.dp(context,9))
            addView(label(title,12f,NeonUi.ink,true))
            addView(NeonUi.gap(this@BtRobotActivity,6))
            addView(field,LinearLayout.LayoutParams(-1,NeonUi.dp(this@BtRobotActivity,48)))
            addView(label(hint,10f,NeonUi.muted))
        }
        return card to field
    }
    private fun parameters():RobotTradingParams=RobotTradingParams.parse(robotCapital.text.toString(),robotHoldings.text.toString(),robotProfit.text.toString())
    private fun dates()=selectedDates
    private fun showDates(value:RobotBacktestDates){
        selectedDates=value
        robotRange.text="測試日期：${value.start} → ${value.end}"
        robotStartDate.text="起始日\n${value.start}"
        robotEndDate.text="結束日\n${value.end}"
        robotStartDate.contentDescription="選擇測試起始日期，目前 ${value.start}"
        robotEndDate.contentDescription="選擇測試結束日期，目前 ${value.end}"
    }
    /** Pick dates independently, then validate the complete interval before creating a new run. */
    private fun pickDate(start:Boolean){
        if(busy)return
        val day=if(start)dates().start else dates().end
        val latest=LocalDate.now(RuleMetrics.TAIPEI).minusDays(1)
        val earliest=LocalDate.of(2016,1,1)
        if(latest<earliest){
            Toast.makeText(this,"尚無可供回測的歷史交易日期",Toast.LENGTH_LONG).show();return
        }
        val initial=day.coerceIn(earliest,latest)
        val picker=DatePickerDialog(this,{_,y,m,d->
            val next=LocalDate.of(y,m+1,d)
            showDates(if(start)dates().copy(start=next) else dates().copy(end=next))
            if(::status.isInitialized)render() else refreshParameterHint()
        },initial.year,initial.monthValue-1,initial.dayOfMonth)
        picker.datePicker.minDate=earliest.atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
        picker.datePicker.maxDate=latest.atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
        picker.show()
    }
    private fun selectedSweep():BtRobotSweep?=if(sweepCheck.isChecked)
        BtRobotSweep.parse(hFrom.text.toString(),hTo.text.toString(),pFrom.text.toString(),pTo.text.toString()) else null
    private fun rangeInput(tagName:String):EditText=EditText(this).apply{
        tag=tagName
        inputType=InputType.TYPE_CLASS_NUMBER
        isSingleLine=true;textSize=18f
        gravity=Gravity.CENTER
        setTextColor(NeonUi.ink);setHintTextColor(NeonUi.muted)
        setPadding(NeonUi.dp(context,4),NeonUi.dp(context,5),NeonUi.dp(context,4),NeonUi.dp(context,5))
        background=NeonUi.panel(this@BtRobotActivity,NeonUi.cyan)
        minHeight=NeonUi.dp(context,46);setSelectAllOnFocus(true)
    }
    private fun rangeCard(caption:String,field:EditText):LinearLayout=NeonUi.vertical(this).apply{
        addView(label(caption,11f,NeonUi.ink))
        addView(field,LinearLayout.LayoutParams(-1,NeonUi.dp(this@BtRobotActivity,48)))
    }
    private fun buildSweepPanel():LinearLayout {
        val c=NeonUi.vertical(this).apply{
            background=NeonUi.panel(this@BtRobotActivity,NeonUi.cyan)
            setPadding(NeonUi.dp(this@BtRobotActivity,10),NeonUi.dp(this@BtRobotActivity,10),
                NeonUi.dp(this@BtRobotActivity,10),NeonUi.dp(this@BtRobotActivity,10))
        }
        sweepCheck=CheckBox(this).apply{
            tag="robot-grid-mode";text="自動範圍測試 · 持倉 × 淨利"
            textSize=15f;setTextColor(NeonUi.cyan)
            contentDescription="啟用固定 ABCDEF 條件的持倉、淨利下車全組合回測"
        }
        c.addView(sweepCheck)
        c.addView(label("固定目前 ABCDEF 勾選、產業、日期與本金。依下方範圍逐組測試，保留結果並自動結束。",11f))
        sweepFields=NeonUi.vertical(this).apply{tag="robot-grid-fields"}
        hFrom=rangeInput("robot-holding-from");hTo=rangeInput("robot-holding-to")
        pFrom=rangeInput("robot-profit-from");pTo=rangeInput("robot-profit-to")
        sweepFields.addView(NeonUi.gap(this,8))
        sweepFields.addView(label("持倉檔數範圍 · 5～50（間隔 1 檔）",12f,NeonUi.amber,true))
        sweepFields.addView(NeonUi.row(this,listOf(rangeCard("起始持倉",hFrom),rangeCard("結束持倉",hTo))))
        sweepFields.addView(NeonUi.gap(this,8))
        sweepFields.addView(label("淨利下車範圍 · 1%～30%（間隔 1%）",12f,NeonUi.pink,true))
        sweepFields.addView(NeonUi.row(this,listOf(rangeCard("起始淨利 %",pFrom),rangeCard("結束淨利 %",pTo))))
        sweepPreview=label("",12f,NeonUi.cyan,true).apply{tag="robot-grid-total"}
        sweepFields.addView(NeonUi.gap(this,8));sweepFields.addView(sweepPreview)
        c.addView(sweepFields)
        sweepCheck.setOnCheckedChangeListener{_,enabled->
            sweepFields.visibility=if(enabled)View.VISIBLE else View.GONE
            if(::robotHoldings.isInitialized){
                robotHoldings.isEnabled=!enabled;robotProfit.isEnabled=!enabled
                robotHoldings.alpha=if(enabled).55f else 1f
                robotProfit.alpha=if(enabled).55f else 1f
            }
            exclusionButtons.forEach{(type,button)->button.text="${type.name.take(1)} 排除 ${robotExclusions.getValue(type).size}"}
        if(::paramNotice.isInitialized)refreshParameterHint()
        }
        return c
    }

    private fun displayParameters(settings:BtSettings,restoreDraft:Boolean=false){
        val values=RobotTradingParams.of(settings)
        val prefs=getSharedPreferences("robot-params-draft",0)
        val useDraft=restoreDraft&&prefs.getString("session",null)==store.active()
        fun candidate(key:String,default:String)=if(useDraft)prefs.getString(key,default).orEmpty() else default
        robotCapital.setText(candidate("capital",btPercent(values.capital)))
        robotHoldings.setText(candidate("holdings",values.maxHoldings.toString()))
        robotProfit.setText(candidate("profit",btPercent(values.targetPct)))
        trendHoldCheck.isChecked=if(useDraft)prefs.getBoolean("profitTrendHold",settings.holdProfitUntilMa5Break) else settings.holdProfitUntilMa5Break
        enabledRadars=if(useDraft)prefs.getStringSet("activeRadars",settings.enabledRadars.map{it.name}.toSet())
            .orEmpty().mapNotNull{runCatching{RadarType.valueOf(it)}.getOrNull()}.toSet()
            else settings.enabledRadars
        robotExclusions=if(useDraft)ExclusionConditions.read(prefs.getString("exclusions",null)?.let{org.json.JSONObject(it)}) else settings.exclusions
        robotEConfig=if(useDraft)runCatching{EPatternConfig.read(org.json.JSONObject(prefs.getString("eConfig",settings.eConfig.json().toString())?:settings.eConfig.json().toString()))}.getOrDefault(settings.eConfig) else settings.eConfig
        robotBConfig=if(useDraft)runCatching{BPatternConfig.read(org.json.JSONObject(prefs.getString("bConfig",settings.bConfig.json().toString())?:settings.bConfig.json().toString()))}.getOrDefault(settings.bConfig) else settings.bConfig
        robotFConfig=if(useDraft)runCatching{FPatternConfig.read(org.json.JSONObject(prefs.getString("fConfig",settings.fConfig.json().toString())?:settings.fConfig.json().toString()))}.getOrDefault(settings.fConfig) else settings.fConfig
        robotDConfig=if(useDraft)runCatching{DPatternConfig.read(org.json.JSONObject(
            prefs.getString("dConfig",settings.dConfig.json().toString())?:settings.dConfig.json().toString()
        ))}.getOrDefault(settings.dConfig) else settings.dConfig
        refreshRadarModes()
        val savedDates=RobotBacktestDates.of(settings)
        val dateDraft=if(useDraft)runCatching{RobotBacktestDates.parse(
            prefs.getString("dateStart",savedDates.start.toString()).orEmpty(),
            prefs.getString("dateEnd",savedDates.end.toString()).orEmpty())}.getOrNull() else null
        showDates(dateDraft?:savedDates)
        val grid=BtRobotSweep.read(store.session()?.optJSONObject("sweep"))?:BtRobotSweep()
        fun gridValue(key:String,default:Int)=if(useDraft)prefs.getString(key,default.toString()).orEmpty() else default.toString()
        hFrom.setText(gridValue("holdingFrom",grid.holdingFrom))
        hTo.setText(gridValue("holdingTo",grid.holdingTo))
        pFrom.setText(gridValue("profitFrom",grid.profitFrom))
        pTo.setText(gridValue("profitTo",grid.profitTo))
        sweepCheck.isChecked=if(useDraft)prefs.getBoolean("gridEnabled",false) else
            store.session()==null||BtRobotSweep.read(store.session()?.optJSONObject("sweep"))!=null
        fieldsSession=store.active()
    }
    private fun refreshParameterHint(){
        val active=store.session()
        val parsed=runCatching{parameters()}.getOrNull()
        val grid=runCatching{selectedSweep()}.getOrNull()
        val invalidGrid=sweepCheck.isChecked&&grid==null
        val dateError=runCatching{dates().validated()}.exceptionOrNull()?.message
        val activeGrid=BtRobotSweep.read(active?.optJSONObject("sweep"))
        val matching=active?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}
        sweepPreview.text=when{
            invalidGrid->"範圍有誤，持倉需 5～50、淨利需 1%～30%，且起點不可大於終點"
            grid!=null->"共 ${grid.total} 組：${grid.holdingFrom}～${grid.holdingTo} 檔 × ${grid.profitFrom}%～${grid.profitTo}%；按開始後逐組執行"
            else->""
        }
        sweepPreview.setTextColor(if(invalidGrid)NeonUi.amber else NeonUi.cyan)
        paramNotice.text=when{
            parsed==null->"參數有誤：本金須大於 0、持倉為正整數、獲利目標 0.01～1000%。"
            invalidGrid->"持倉／淨利範圍輸入錯誤，請先修改。"
            dateError!=null->"測試日期：$dateError；請修改起始日或結束日。"
            active==null->"填寫後按「開始測試」即建立新批次；結果獨立保存。"
            matching?.enabledRadars!=enabledRadars||matching.dConfig!=robotDConfig||matching.eConfig!=robotEConfig||matching.fConfig!=robotFConfig||matching.bConfig!=robotBConfig||matching.exclusions!=robotExclusions||
            dates()!=RobotBacktestDates.of(BtSettingsCodec.decode(active.getJSONObject("settings")))||
            activeGrid!=grid||RobotTradingParams.of(BtSettingsCodec.decode(active.getJSONObject("settings")))!=parsed||
            BtSettingsCodec.decode(active.getJSONObject("settings")).holdProfitUntilMa5Break!=trendHoldCheck.isChecked->
                "測試設定已修改；按開始會另建獨立批次，不覆蓋舊結果。"
            else->"本批次條件已保存；可暫停續跑。範圍測試完畢後會自動停止。"
        }
        paramNotice.setTextColor(if(parsed==null||invalidGrid||dateError!=null)NeonUi.amber else NeonUi.muted)
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);store=BtRobotStore(this);exportId=savedInstanceState?.getString("exportId").orEmpty()
        val root=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,28))}
        val scroll=ScrollView(this).apply{background=NeonUi.backdrop(context);addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        root.addView(NeonUi.header(this,"自動測試機器人","AUTOMATION LAB"){finish()})
        val toolsRow=NeonUi.row(this,listOf(NeonUi.button(this,"禁股名單",NeonUi.pink){StockToolsActivity.open(this,"BAN",StockScope.ROBOT)},NeonUi.button(this,"限價名單",NeonUi.amber){StockToolsActivity.open(this,"LIMIT",StockScope.ROBOT)},NeonUi.button(this,"搜尋股票"){StockToolsActivity.open(this,"SEARCH",StockScope.ROBOT)}));root.addView(toolsRow)
        root.addView(NeonUi.button(this,"匯入主動買／賣成交量 CSV",NeonUi.cyan){
            if(!busy)startActivity(Intent(this,TradeSideVolumeImportActivity::class.java))
        }.apply{tag="robot-flow-import"})
        root.addView(NeonUi.gap(this,8))
        val options=panel(NeonUi.cyan).apply{tag="robot-condition-settings"}
        options.addView(label("① 啟用類別 · 彩色已啟用／灰色已停用",12f,NeonUi.cyan))
        options.addView(NeonUi.radarRows(this,RadarType.entries.map{type->
            NeonUi.button(this,"",NeonUi.cyan){
                enabledRadars=if(type in enabledRadars)enabledRadars-type else enabledRadars+type
                refreshRadarModes()
            }.apply{tag="robot-enabled-${type.name}";textSize=12f}.also{radarModeButtons[type]=it}
        }))
        options.addView(NeonUi.gap(this,18))
        options.addView(label("② 設定條件 · 點擊編輯必選／排除",15f,NeonUi.ink,true))
        options.addView(NeonUi.radarRows(this,RadarType.entries.map{type->
            NeonUi.button(this,"${type.label}\n設定條件 ▾",NeonUi.mint){openRuleOptions(type)}
                .apply{tag="robot-rules-${type.name}";textSize=11f}.also{radarRuleButtons[type]=it}
        }))
        options.addView(NeonUi.radarRows(this,RadarType.entries.map{type->NeonUi.button(this,"${type.name.take(1)} 排除",NeonUi.pink){
            ExclusionEditor.show(this,type,robotExclusions.getValue(type)){ids->robotExclusions=robotExclusions+mapOf(type to ids);refreshRadarModes()}
        }.apply{tag="robot-exclusions-${type.name}";textSize=11f}.also{exclusionButtons[type]=it}}))
        options.addView(label("各區條件：單點必選、空白自由測試、雙擊排除；設定不會改變上方啟停開關。",11f))
        options.addView(NeonUi.button(this,"D 主力進場 · 數值門檻",NeonUi.mint){
            DPatternEditor.show(this,robotDConfig){cfg->robotDConfig=cfg;refreshRadarModes()}
        }.apply{tag="robot-d-parameters"})
        options.addView(NeonUi.button(this,"E DIF金叉 · 線距與交叉設定",NeonUi.pink){
            EPatternEditor.show(this,robotEConfig){cfg->robotEConfig=cfg;refreshRadarModes()}
        }.apply{tag="robot-e-parameters"})
        options.addView(NeonUi.button(this,"F 縮量上漲 · 天數與門檻",NeonUi.pink){
            FPatternEditor.show(this,robotFConfig){cfg->robotFConfig=cfg;refreshRadarModes()}
        }.apply{tag="robot-f-parameters"})
        options.addView(NeonUi.gap(this,12))
        sectorButton=NeonUi.button(this,"產業保留／排除",NeonUi.mint){
            if(!busy){hideInput();val settings=store.session()?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
                RobotSectorDialog.show(this,settings.sectors){applySectors(it)}}
        }.apply{tag="robot-sectors"};options.addView(sectorButton)
        ruleSummary=label("",11f,NeonUi.cyan).apply{tag="robot-rule-summary"};options.addView(ruleSummary)
        root.addView(options)
        val fixed=panel(NeonUi.cyan)
        fixed.addView(label("測試範圍與可調整參數",15f,NeonUi.ink,true));fixed.addView(NeonUi.gap(this,6))
        robotRange=label("",14f,NeonUi.cyan,true).apply{tag="robot-date-range"}
        fixed.addView(robotRange)
        fixed.addView(NeonUi.gap(this,8))
        robotStartDate=NeonUi.button(this,"起始日",NeonUi.cyan){pickDate(true)}
            .apply{tag="robot-start-date";isAllCaps=false;minHeight=NeonUi.dp(this@BtRobotActivity,58)}
        robotEndDate=NeonUi.button(this,"結束日",NeonUi.cyan){pickDate(false)}
            .apply{tag="robot-end-date";isAllCaps=false;minHeight=NeonUi.dp(this@BtRobotActivity,58)}
        fixed.addView(NeonUi.row(this,listOf(robotStartDate,robotEndDate)))
        fixed.addView(NeonUi.gap(this,6))
        fixed.addView(label("點日期可選擇歷史回測區間；只使用已結束的日期，最長 730 天。修改後會另建測試批次。",11f))
        fixed.addView(NeonUi.gap(this,10))
        val cash=paramBox("初始本金","元 · 不融資",NeonUi.cyan,"robot-capital-input",true)
        val holding=paramBox("持倉上限","檔 · 不同股號",NeonUi.amber,"robot-holdings-input",false)
        val profit=paramBox("淨利下車","% · 扣費稅",NeonUi.pink,"robot-profit-input",true)
        robotCapital=cash.second;robotHoldings=holding.second;robotProfit=profit.second
        fixed.addView(NeonUi.row(this,listOf(cash.first,holding.first,profit.first)))
        trendHoldCheck=CheckBox(this).apply{
            tag="robot-profit-trend-hold";textSize=13f;setTextColor(NeonUi.cyan)
            text="淨利達標 RSI(14)>50 且日K未跌破5MA 續抱；日K收盤跌破5MA 才賣出"
            setOnCheckedChangeListener{_,_->if(::paramNotice.isInitialized)refreshParameterHint()}
        }
        fixed.addView(trendHoldCheck)
        paramNotice=label("",11f).apply{tag="robot-parameter-notice"}
        fixed.addView(NeonUi.gap(this,8));fixed.addView(paramNotice)
        fixed.addView(NeonUi.gap(this,6));fixed.addView(label("每次各買 1,000 股；超過 5 天可保本賣出，保留折讓金。測試日期、股票範圍、ABCDEF 與產業採用目前批次快照；可從回測日誌完整同步。",11f))
        fixed.addView(NeonUi.gap(this,10))
        fixed.addView(buildSweepPanel())
        val snapshot=store.session()?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
        displayParameters(snapshot,restoreDraft=true)
        root.addView(fixed)
        root.addView(NeonUi.button(this,"下車賣出條件 · 5 組選填",NeonUi.amber){
            if(!busy){
                val prior=store.session()?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
                ExitRulesEditor.show(this,"自動測試機器人",prior.exitRules){rules->applyExitRules(rules)}
            }
        }.apply{tag="robot-exit-rules"})
        root.addView(label("修改下車條件會保存成新批次、保留舊結果；按開始測試才執行。",11f))
        root.addView(NeonUi.gap(this,12))
        val configLast=root.getChildAt(root.childCount-1)
        val progress=panel(NeonUi.amber);progress.tag="robot-progress"
        progress.addView(MarketGuardPanel(this,"所有組合共用同一份大盤歷史快照"));progress.addView(NeonUi.gap(this,10))
        progress.addView(label("組合探索進度",16f,NeonUi.ink,true));progress.addView(NeonUi.gap(this,8))
        status=label("尚未開始",13f,NeonUi.amber);progress.addView(status)
        counts=label("已測試 0 組",13f,NeonUi.cyan,true);counts.setPadding(0,NeonUi.dp(this,10),0,NeonUi.dp(this,10));progress.addView(counts)
        best=label("目前已測最佳：—",23f,NeonUi.pink,true);progress.addView(best)
        coverage=label("首次取得日線後，固定保存本次資料",11f);progress.addView(NeonUi.gap(this,8));progress.addView(coverage)
        root.addView(progress);root.addView(NeonUi.gap(this,10))
        startButton=NeonUi.primary(this,"開始測試"){startTesting()}
        pauseButton=NeonUi.button(this,"暫停",NeonUi.amber){if(!busy)BtRobotWorker.pause(this,store.active());render()}
        root.addView(NeonUi.row(this,listOf(startButton,pauseButton)))
        root.addView(NeonUi.gap(this,6))
        bestButton=NeonUi.button(this,"目前最佳 · 條件與套用",NeonUi.pink){if(!busy)store.session()?.let{showBestRules(it)}};root.addView(bestButton)
        root.addView(NeonUi.gap(this,6))
        topButton=NeonUi.button(this,"最佳前 10 組 · 收益排行榜",NeonUi.cyan){if(!busy)showRankings()}
            .apply{tag="robot-grid-ranking"}
        root.addView(topButton)
        root.addView(NeonUi.gap(this,6))
        val manageFirst=NeonUi.row(this,listOf(NeonUi.button(this,"未測組合"){untested()},NeonUi.button(this,"測試紀錄"){sessions()}));root.addView(manageFirst)
        root.addView(NeonUi.gap(this,6))

        root.addView(NeonUi.gap(this,6))
        root.addView(NeonUi.button(this,"一鍵清除零收益",NeonUi.amber){clearZeros()}.apply{tag="robot-clear-zero"})
        root.addView(NeonUi.gap(this,6))
        root.addView(NeonUi.row(this,listOf(NeonUi.button(this,"匯出結果／斷點"){exportCheckpoint()},NeonUi.button(this,"匯入並續跑"){importCheckpoint()})))
        root.addView(NeonUi.gap(this,6));root.addView(NeonUi.button(this,"建立新測試",NeonUi.amber){
            if(!busy){
                val entered=runCatching{parameters()}.getOrElse{
                    Toast.makeText(this,"請先修正測試參數：${it.message}",Toast.LENGTH_LONG).show()
                    return@button
                }
                val sweep=runCatching{selectedSweep()}.getOrElse{
                    Toast.makeText(this,"請先修正範圍：${it.message}",Toast.LENGTH_LONG).show()
                    return@button
                }
                val chosenDates=runCatching{dates().validated()}.getOrElse{
                    Toast.makeText(this,"請先修正測試日期：${it.message}",Toast.LENGTH_LONG).show()
                    return@button
                }
                MaterialAlertDialogBuilder(this).setTitle("建立新的測試批次")
                    .setMessage("會保留目前批次的全部結果，使用你選定的 ${chosenDates.start} → ${chosenDates.end} 歷史日期，以及目前 ABCDEF、產業、股號範圍、下車規則和輸入的資金／持倉／獲利設定。日期改變時重新取得該期間的歷史行情，不沿用舊區間資料。建立後按開始才測試。")
                    .setNegativeButton("取消",null).setPositiveButton("建立"){_,_->
                        runTask("正在建立新的測試批次…"){
                            val previous=store.session()
                            val settings=chosenDates.applyTo(entered.applyTo(previous?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()))
                                .copy(enabledRadars=enabledRadars,dConfig=robotDConfig,eConfig=robotEConfig,fConfig=robotFConfig,bConfig=robotBConfig,exclusions=robotExclusions,stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(this).read(),
                                    stockScope=StockScope.ROBOT,marketGuardVersion=MarketCrashGuard.VERSION,
                                    holdProfitUntilMa5Break=trendHoldCheck.isChecked)
                            BtRobotSpace.validate(settings)
                            val original=store.active()
                            val mustHave=if(sweep!=null)"0" else required()
                            val excludes=if(sweep!=null)"0" else forbidden()
                            if(original.isNotBlank())BtRobotWorker.pause(this,original)
                            store.create(RobotRuleSelection.prepare(settings,fixedRules=sweep!=null),mustHave,excludes,
                                previous?.takeIf{!it.isNull("sourceJournalId")}?.optString("sourceJournalId"),sweep)
                            "已建立新批次，請按開始測試；舊結果保留"
                        }
                    }.show()
            }
        })
        root.addView(NeonUi.gap(this,10))
        spaceLabel=label("",12f);root.addView(spaceLabel)
        root.addView(label("目前已測最佳 ≠ 全組合最高。固定區間反覆調整可能過度擬合，不代表未來獲利；總損益含留倉、股息與估計折讓金。",12f,NeonUi.amber))
        root.addView(label("勾選財報／法人但缺歷史證據時不通過。意外中斷 10 秒後自動續跑，手動暫停不自啟。歷史條件首次預算完成後保存，續跑直接讀取。Android 省電／背景限制可能延後啟動；每組完成即保存。",11f))
        val manageLast=root.getChildAt(root.childCount-1)
        root.addView(NeonUi.gap(this,16));root.addView(label("已測試組合",19f,NeonUi.ink,true))
        records=NeonUi.vertical(this);root.addView(records)
        // Keep execution and best result above configuration and maintenance tools.
        val controls=NeonUi.vertical(this)
        val from=root.indexOfChild(progress);val end=root.indexOfChild(topButton)
        repeat(end-from+1){val child=root.getChildAt(from);root.removeViewAt(from);controls.addView(child)}
        root.addView(controls,1)
        root.removeView(toolsRow);fixed.addView(NeonUi.gap(this,8));fixed.addView(toolsRow)
        NeonUi.group(root,fixed,configLast,"測試參數與下車條件","本金、持倉、淨利可編輯；沿用快照日期及認賠／風險退出條件","robot-settings")
        NeonUi.group(root,manageFirst,manageLast,"批次管理","測試紀錄、匯入匯出與續跑","robot-management")
        render()
    }
    override fun onResume(){super.onResume();visible=true;handler.post(poll)}
    override fun onPause(){
        visible=false;handler.removeCallbacks(poll)
        if(::robotCapital.isInitialized)getSharedPreferences("robot-params-draft",0).edit()
            .putString("session",store.active()).putString("capital",robotCapital.text.toString())
            .putString("dateStart",dates().start.toString()).putString("dateEnd",dates().end.toString())
            .putString("holdings",robotHoldings.text.toString()).putString("profit",robotProfit.text.toString())
            .putBoolean("gridEnabled",sweepCheck.isChecked).putBoolean("profitTrendHold",trendHoldCheck.isChecked)
            .putStringSet("activeRadars",enabledRadars.map{it.name}.toSet())
            .putString("dConfig",robotDConfig.json().toString()).putString("eConfig",robotEConfig.json().toString()).putString("fConfig",robotFConfig.json().toString()).putString("bConfig",robotBConfig.json().toString()).putString("exclusions",ExclusionConditions.json(robotExclusions).toString())
            .putString("holdingFrom",hFrom.text.toString()).putString("holdingTo",hTo.text.toString())
            .putString("profitFrom",pFrom.text.toString()).putString("profitTo",pTo.text.toString()).apply()
        super.onPause()
    }
    private fun base():BtSettings {
        val saved=runCatching{BtSettingsCodec.decode(BacktestStore(this).configuration()!!)}.getOrNull()
            ?:BtSettingsCodec.capture(this,java.time.LocalDate.of(2026,8,1),java.time.LocalDate.of(2026,10,8),5000000.0,"")
        return BtRobotSpace.settings(saved.rules,saved.sectors.ifEmpty{StockSector.entries.toSet()}).copy(
            enabledRadars=saved.enabledRadars,dConfig=saved.dConfig,eConfig=saved.eConfig,fConfig=saved.fConfig,bConfig=saved.bConfig,exclusions=saved.exclusions,stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(this).read(),stockScope=StockScope.ROBOT,exitRules=com.rex.twboardingscanner.data.ExitRuleStore(this).read(StockScope.ROBOT),marketGuardVersion=MarketCrashGuard.VERSION,
            holdProfitUntilMa5Break=com.rex.twboardingscanner.data.ExitRuleStore(this).readProfitTrendHold(StockScope.ROBOT))
    }
    /** Changed money/holding/exit targets must always become a NEW independent batch. */
    internal fun policySession(params:RobotTradingParams,sweep:BtRobotSweep?,dateRange:RobotBacktestDates):String {
        val old=store.session()
        val saved=old?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}
        val previousSweep=BtRobotSweep.read(old?.optJSONObject("sweep"))
        val original=saved?:base()
        val policy=com.rex.twboardingscanner.data.StockPolicyStore(this).read()
        val next=RobotRuleSelection.prepare(dateRange.applyTo(params.applyTo(original))
            .copy(enabledRadars=enabledRadars,dConfig=robotDConfig,eConfig=robotEConfig,fConfig=robotFConfig,bConfig=robotBConfig,exclusions=robotExclusions,stockPolicy=policy,stockScope=StockScope.ROBOT,marketGuardVersion=MarketCrashGuard.VERSION,
                holdProfitUntilMa5Break=trendHoldCheck.isChecked),fixedRules=sweep!=null)
        BtRobotSpace.validate(next)
        if(old!=null&&old.optInt("analysisVersion")>=2&&old.optInt("searchVersion")>=6&&next==saved&&sweep==previousSweep)return old.getString("id")
        val oldId=old?.optString("id").orEmpty()
        val required=if(sweep!=null)"0" else old?.optString("required","0")?:required()
        val forbidden=if(sweep!=null)"0" else old?.optString("forbidden","0")?:forbidden()
        if(oldId.isNotBlank())BtRobotWorker.pause(this,oldId)
        val source=old?.takeIf{!it.isNull("sourceJournalId")}?.optString("sourceJournalId")?.takeIf{it.isNotBlank()}
        val created=store.create(next,required,forbidden,source,sweep)
        if(oldId.isNotBlank()&&saved!=null&&saved.start==next.start&&saved.end==next.end
            &&saved.codes==next.codes&&saved.sectors==next.sectors&&saved.marketGuardVersion==next.marketGuardVersion)
            BtRobotCheckpoint.cloneData(store,oldId,created)
        return created
    }
    private fun startTesting(){
        if(busy)return
        val params=runCatching{parameters()}.getOrElse{
            Toast.makeText(this,"參數錯誤：${it.message}",Toast.LENGTH_LONG).show()
            refreshParameterHint();return
        }
        val grid=runCatching{selectedSweep()}.getOrElse{
            Toast.makeText(this,"範圍錯誤：${it.message}",Toast.LENGTH_LONG).show()
            refreshParameterHint();return
        }
        val range=runCatching{dates().validated()}.getOrElse{
            Toast.makeText(this,"日期錯誤：${it.message}",Toast.LENGTH_LONG).show()
            refreshParameterHint();return
        }
        busy=true;startButton.isEnabled=false
        Thread{
            val result=runCatching{val id=policySession(params,grid,range);BtRobotWorker.start(this,id)}
            runOnUiThread{if(!isFinishing&&!isDestroyed){
                busy=false
                result.onFailure{Toast.makeText(this,"無法開始：${it.message}",Toast.LENGTH_LONG).show()}
                render()
            }}
        }.start()
    }
    internal fun render(){
        if(busy)return
        val s=store.session();val id=s?.optString("id").orEmpty()
        if(lastId!=id){lastId=id;page=0;stamp=""}
        if(fieldsSession!=id){
            val source=s?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
            displayParameters(source)
        }
        refreshParameterHint()
        val tested=s?.optLong("tested")?:0L
        val legacySpace=(s?.optInt("searchVersion",3)?:3)<3
        val sessionSweep=BtRobotSweep.read(s?.optJSONObject("sweep"))
        val desiredSweep=runCatching{selectedSweep()}.getOrNull()
        val invalidSweep=sweepCheck.isChecked&&desiredSweep==null
        val activeRuleSet=if(s==null)enabledRadars else BtSettingsCodec.decode(s.getJSONObject("settings")).enabledRadars
        val searchVersion=s?.optInt("searchVersion",6)?:6
        val total=(sessionSweep?:if(s==null)desiredSweep else null)?.total?.let{BigInteger.valueOf(it.toLong())}
            ?:BtRobotSpace.total(required(),forbidden(),legacy=legacySpace,
                enabled=if(searchVersion<4)defaultActiveRadars() else activeRuleSet,
                includeD=searchVersion>=4,includeCost=searchVersion>=5,includeE=searchVersion>=6)
        val remaining=total-BigInteger.valueOf(tested)
        val fixed=BtRobotSpace.required(required()).bitCount();val blocked=BtRobotSpace.required(forbidden()).bitCount()
        ruleSummary.text=if(sweepCheck.isChecked)"目前為持倉×淨利網格；啟用區域若無固定條件，開始時會補上該區預設條件。儲存區域條件可切換自由組合測試。"
            else "目前條件：必選 $fixed／排除 $blocked；其餘由機器人自由測試"
        val selectedRules=BtRobotSpace.rules(required());val excludedRules=BtRobotSpace.rules(forbidden())
        radarRuleButtons.forEach{(type,button)->
            button.text="${type.label} · 設定\n必選 ${selectedRules[type].orEmpty().size}／排除 ${excludedRules[type].orEmpty().size}"
            button.isEnabled=!busy
        }
        val sectors=s?.let{BtSettingsCodec.decode(it.getJSONObject("settings")).sectors}?:base().sectors
        sectorButton.text="產業選單 · 保留 ${sectors.size}／排除 ${StockSector.entries.size-sectors.size}"
        sectorButton.isEnabled=!busy
        val effectiveSweep=sessionSweep?:if(s==null)desiredSweep else null
        spaceLabel.text=if(effectiveSweep!=null)
            "持倉 ${effectiveSweep.holdingFrom}～${effectiveSweep.holdingTo} 檔 × 淨利 ${effectiveSweep.profitFrom}%～${effectiveSweep.profitTo}% · 共 ${effectiveSweep.total} 組。當前 ABCDEF 啟用區域固定，逐組測試直到全部完成."
        else "必選 $fixed 項 · 排除 $blocked 項 · 啟用 ${activeRuleSet.joinToString(""){it.name.take(1)}} · 共 ${num(total)} 種 ABCDEF 組合。"

        val policyChanged=s?.let{BtSettingsCodec.decode(it.getJSONObject("settings")).stockPolicy!=com.rex.twboardingscanner.data.StockPolicyStore(this).read()}?:false
        val guardChanged=s?.getJSONObject("settings")?.optInt("marketGuardVersion",0)==0
        val running=!guardChanged&&s?.optString("state") in setOf("RUNNING","RETRY")
        val entered=runCatching{parameters()}.getOrNull()
        val dateError=runCatching{dates().validated()}.exceptionOrNull()?.message
        val matching=s?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}
        val changed=s!=null&&(s.optInt("analysisVersion")<2||sessionSweep!=desiredSweep||
            matching?.enabledRadars!=enabledRadars||matching.dConfig!=robotDConfig||matching.eConfig!=robotEConfig||matching.fConfig!=robotFConfig||matching.bConfig!=robotBConfig||matching.exclusions!=robotExclusions||
            dates()!=RobotBacktestDates.of(BtSettingsCodec.decode(s.getJSONObject("settings")))||
            entered!=null&&RobotTradingParams.of(BtSettingsCodec.decode(s.getJSONObject("settings")))!=entered)
        val invalid=entered==null||invalidSweep||dateError!=null
        status.text=when{
            dateError!=null->"測試日期錯誤：$dateError"
            invalid->"資金／持倉／淨利範圍有無效數字，請修改後再開始"
            changed->"條件引擎、日期、參數或模式已更新；按開始將建立新批次並保留舊績效"
            guardChanged->"此舊批次未啟用大盤保護；按開始建立受保護新批次，舊績效保留"
            policyChanged->"禁股／限價已更新；按開始將建立新批次並沿用快取"
            else->s?.optString("message")?:"按開始測試，自動尋找更高收益組合"
        }
        counts.text=if(effectiveSweep==null)
            "已測試 ${num(tested)} 組\n未測試 ${num(remaining)} 組\n已清理 ${num(s?.optLong("cleared")?:0L)} 組零收益"
        else "參數測試進度：$tested / ${effectiveSweep.total} 組（${String.format(Locale.TAIWAN,"%.1f",tested.toDouble()/effectiveSweep.total*100)}%）\n剩餘 ${num(remaining)} 組 · 已清理 ${num(s?.optLong("cleared")?:0L)} 組"
        val key=s?.optString("bestKey").orEmpty()
        best.text=if(key.isEmpty())"目前已測最佳：—" else "目前已測最佳\n${signed(s!!.getDouble("bestProfit"))} 元"+
            (sessionSweep?.let{"\n${it.parseKey(key).label}"}?:"")
        if(key.isNotEmpty())best.setTextColor(color(s!!.getDouble("bestProfit")))
        coverage.text=if(s==null)"首次載入資料後固定樣本；結果逐組保存" else {
            val settings=BtSettingsCodec.decode(s.getJSONObject("settings"))
            "批次 ${id.take(8)} · "+(if(s.has("loaded"))"資料 ${s.getInt("loaded")} / ${s.getInt("requested")} 檔"else"資料尚未備妥")+
            "\n日期：${settings.start} → ${settings.end}"+
            "\n本金 ${num(settings.capital)} 元"+(if(sessionSweep==null)" · 持倉 ${settings.maxHoldingStocks} 檔 · 淨利 ${btPercent(settings.targetNetPct)}%"
                else "\n持倉與淨利：按 ${sessionSweep.total} 組可調範圍逐組測試")+
            "\n啟用：${radarLabel(s)} · D 固定 ${BtRobotSpace.rules(s.optString("required","0"))[RadarType.D_MAIN_FORCE].orEmpty().size}／排除 ${BtRobotSpace.rules(s.optString("forbidden","0"))[RadarType.D_MAIN_FORCE].orEmpty().size}"+
            "\n產業："+settings.sectors.sortedBy{it.ordinal}.joinToString("、"){it.label}+
            (if(!s.isNull("sourceJournalId"))"\n來源回測日誌：${s.optString("sourceJournalId").take(8)}" else "")
        }
        startButton.text=when{
            invalid->"請先修正測試參數"
            changed->"套用新參數並開始"
            guardChanged->"啟用大盤保護並開始新批次"
            policyChanged->"套用新名單並開始"
            running->"測試進行中…"
            s?.optString("state")=="DONE"->"全部測試完成"
            tested>0->"繼續／重試"
            else->"開始測試"
        }
        startButton.isEnabled=!busy&&!invalid&&(!running||changed||guardChanged||policyChanged)&&
            (changed||guardChanged||policyChanged||s?.optString("state")!="DONE")
        pauseButton.isEnabled=running;bestButton.isEnabled=key.isNotEmpty()
        topButton.isEnabled=tested>0
        val signature="$id-$tested-${s?.optLong("cleared")?:0}-$page"
        if(stamp!=signature){stamp=signature;renderTrials(id)}
        if(visible&&s!=null&&key.isNotEmpty()&&s.optLong("bestSeq")>s.optLong("ack")&&alert?.isShowing!=true){
            val seq=s.getLong("bestSeq");store.acknowledge(id,seq)
            alert=MaterialAlertDialogBuilder(this).setTitle("發現目前已測最佳")
                .setMessage("第 $seq 組 · 總損益 ${signed(s.getDouble("bestProfit"))} 元"+
                    (sessionSweep?.let{"\n${it.parseKey(key).label}"}?:"")+
                    "\n已測試 ${num(tested)} 組。這是歷史樣本內最佳，不代表未來收益。")
                .setNegativeButton("繼續測試",null).setNeutralButton("查看明細"){_,_->openReport(id,key)}
                .setPositiveButton("查看／套用規則"){_,_->showBestRules(s)}.show()
        }
    }
    private fun settings(s:JSONObject,key:String):BtSettings{
        val base=BtSettingsCodec.decode(s.getJSONObject("settings"))
        return BtRobotSweep.read(s.optJSONObject("sweep"))?.settings(base,key)?:base.copy(rules=BtRobotSpace.rules(key))
    }
    private fun showBestRules(s:JSONObject){val key=s.optString("bestKey");if(key.isNotEmpty())BtSnapshotDialog.show(this,BtSettingsCodec.encode(settings(s,key)),"目前已測最佳 · 條件快照")}
    private fun showRankings(){
        val session=store.session()?:run{Toast.makeText(this,"尚無測試批次",Toast.LENGTH_SHORT).show();return}
        val top=store.topTrials(session.getString("id"),10)
        val grid=BtRobotSweep.read(session.optJSONObject("sweep"))
        val total=grid?.total
        val body=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(this@BtRobotActivity,12),
            NeonUi.dp(this@BtRobotActivity,12),NeonUi.dp(this@BtRobotActivity,12),NeonUi.dp(this@BtRobotActivity,12))}
        body.addView(label(if(total!=null)"已完成 ${session.getLong("tested")} / $total 組 · ${if(session.optString("state")=="DONE")"全部完成" else "持續測試中"}"
            else "${radarLabel(session)} 組合已測 ${session.getLong("tested")} 組",13f,NeonUi.cyan,true))
        body.addView(NeonUi.gap(this,10))
        if(top.isEmpty())body.addView(label("尚無有效結果，第一組完成後就會顯示收益排名。",13f))
        top.forEachIndexed{i,r->
            val key=r.getString("key")
            val pnl=r.getDouble("profit")
            val summary=grid?.parseKey(key)?.label?:BtRobotSpace.rules(key).entries.joinToString(" · "){"${it.key.name.take(1)} ${it.value.size} 項"}
            val tile=panel(if(pnl>=0)NeonUi.pink else NeonUi.mint)
            tile.addView(label("第 ${i+1} 名 · ${signed(pnl)} 元",19f,color(pnl),true))
            tile.addView(label(summary,13f,NeonUi.cyan,true))
            tile.addView(label("總交易：買 ${r.optInt("buys")} 張／賣 ${r.optInt("closed")} 張 · 最大回撤 ${String.format(Locale.TAIWAN,"%.2f",r.getDouble("drawdown"))}%",11f))
            tile.addView(NeonUi.button(this,"查看這組完整成交紀錄"){openReport(session.getString("id"),key)})
            body.addView(tile);body.addView(NeonUi.gap(this,8))
        }
        body.addView(label("排名是同一回測區間的樣本內績效，不代表未來收益。",11f,NeonUi.amber))
        val viewport=ScrollView(this).apply{addView(body)}
        MaterialAlertDialogBuilder(this).setTitle("最佳前 10 組 · 收益排行榜")
            .setView(viewport).setPositiveButton("關閉",null).show()
    }
    private fun openReport(id:String,key:String){startActivity(Intent(this,BacktestActivity::class.java).putExtra("robotSession",id).putExtra("robotKey",key))}
    private fun renderTrials(id:String){
        records.removeAllViews();if(id.isBlank()){records.addView(NeonUi.empty(this,"等待第一組結果","完成測試後，可在這裡檢閱組合條件與完整買賣紀錄。"));return}
        val rows=store.trials(id,20,page*20)
        val sweep=BtRobotSweep.read(store.session(id)?.optJSONObject("sweep"))
        rows.forEach{r->
            val key=r.getString("key");val pnl=r.getDouble("profit");val box=panel(color(pnl))
            box.addView(label("第 ${r.getLong("seq")} 組 · ${BacktestJournalUi.time(r.getLong("finishedAt"))}",11f))
            box.addView(label("${signed(pnl)} 元",24f,color(pnl),true))
            box.addView(label(if(sweep!=null)sweep.parseKey(key).label else
                BtRobotSpace.rules(key).entries.joinToString(" · "){"${it.key.name.take(1)} ${it.value.size} 項"},13f,NeonUi.cyan))
            box.addView(label("買 ${r.getInt("buys")} 張／賣 ${r.getInt("closed")} 張 · 最大回撤 ${String.format(Locale.TAIWAN,"%.2f",r.getDouble("drawdown"))}%",11f))
            r.optJSONObject("signalCounts")?.let{feedback->
                box.addView(label(feedback.keys().asSequence().sorted().joinToString(" · "){"$it 符合 ${feedback.optInt(it)} 筆"}+" · 待查核 ${r.optInt("pendingChecks")} 項",11f,NeonUi.amber))
            }
            box.addView(NeonUi.gap(this,6))
            box.addView(NeonUi.row(this,listOf(NeonUi.button(this,"條件快照"){store.session(id)?.let{BtSnapshotDialog.show(this,BtSettingsCodec.encode(settings(it,key)),"第 ${r.getLong("seq")} 組")}},NeonUi.button(this,"完整日誌"){openReport(id,key)})))
            records.addView(NeonUi.gap(this,8));records.addView(box)
        }
        if(rows.isEmpty())records.addView(label("目前沒有保留的結果；載入資料不計入已測數，清理不會重置已測進度。",12f))
        val total=store.retained(id)
        val previous=NeonUi.button(this,"上一頁"){page--;stamp="";render()}.apply{isEnabled=page>0}
        val next=NeonUi.button(this,"下一頁"){page++;stamp="";render()}.apply{isEnabled=total>(page+1L)*20}
        records.addView(NeonUi.gap(this,8));records.addView(label("第 ${page+1} 頁 · 共 $total 組完整紀錄",12f));records.addView(NeonUi.row(this,listOf(previous,next)))
    }
    private fun untested(){
        if(busy)return
        val s=store.session();if(s==null){Toast.makeText(this,"請先開始或建立測試批次",Toast.LENGTH_SHORT).show();return}
        val id=s.getString("id");val content=NeonUi.vertical(this);val preview=mutableSetOf<String>()
        val grid=BtRobotSweep.read(s.optJSONObject("sweep"))
        var cursor=BigInteger(s.getString("cursor"))
        repeat(10){
            val candidate=if(grid==null)
                BtRobotSpace.next(BtRobotSpace.key(BtSettingsCodec.decode(s.getJSONObject("settings")).rules),
                    s.optString("bestKey").ifBlank{null},cursor,BigInteger(s.getString("seed")),
                    s.optString("required","0"),s.optString("forbidden","0"),
                    legacy=s.optInt("searchVersion",1)<3,
                    enabled=if(s.optInt("searchVersion",1)<4)defaultActiveRadars()
                            else BtSettingsCodec.decode(s.getJSONObject("settings")).enabledRadars,
                    includeD=s.optInt("searchVersion",1)>=4,includeCost=s.optInt("searchVersion",1)>=5,includeE=s.optInt("searchVersion",1)>=6,includeMomentum=s.optInt("searchVersion",1)>=7){it in preview||store.seen(id,it)}
                else grid.next(cursor.toLong())
            if(candidate!=null){
                preview+=candidate.key;cursor=candidate.cursor
                val title=grid?.parseKey(candidate.key)?.label?:
                    BtRobotSpace.rules(candidate.key).entries.joinToString(" · "){"${it.key.name.take(1)} ${it.value.size} 項"}
                content.addView(NeonUi.button(this,title){BtSnapshotDialog.show(this,BtSettingsCodec.encode(settings(s,candidate.key)),"尚未測試 · 候選規則")})
                content.addView(NeonUi.gap(this,6))
            }
        }
        val wrap=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(context,16),NeonUi.dp(context,10),NeonUi.dp(context,16),NeonUi.dp(context,10));addView(label("未測試 ${num(store.remaining(id))} 組\n以下預覽最多 10 組；${if(grid!=null)"依持倉、淨利數值逐一掃描，全部測完即結束。"else"找到新最佳後順序可能調整。"}",12f,NeonUi.ink));addView(content)}
        MaterialAlertDialogBuilder(this).setTitle("未測試組合").setView(ScrollView(this).apply{addView(wrap)}).setPositiveButton("關閉",null).show()
    }
    private fun hideInput(){
        currentFocus?.let{(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken,0);it.clearFocus()}
    }
    private fun openRuleOptions(type:RadarType){
        if(busy)return
        hideInput()
        BtRobotRulesDialog.show(this,required(),forbidden(),enabledRadars,type,robotDConfig,robotFConfig,bConfig=robotBConfig,onBParameters={robotBConfig=it},
            onParameters={d,f->robotDConfig=d;robotFConfig=f}){on,off->applyRequired(on,off)}
    }
    internal fun applySectors(sectors:Set<StockSector>){
        if(busy)return
        if(sectors.isEmpty()){Toast.makeText(this,"請至少保留一個產業",Toast.LENGTH_LONG).show();return}
        val previous=store.session()
        val saved=previous?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()
        if(saved.sectors==sectors)return
        val entered=runCatching{parameters()}.getOrElse{Toast.makeText(this,it.message,Toast.LENGTH_LONG).show();return}
        val range=runCatching{dates().validated()}.getOrElse{Toast.makeText(this,it.message,Toast.LENGTH_LONG).show();return}
        val grid=runCatching{selectedSweep()}.getOrElse{Toast.makeText(this,it.message,Toast.LENGTH_LONG).show();return}
        val settings=range.applyTo(entered.applyTo(saved)).copy(sectors=sectors,
            enabledRadars=enabledRadars,dConfig=robotDConfig,eConfig=robotEConfig,fConfig=robotFConfig,bConfig=robotBConfig,exclusions=robotExclusions,holdProfitUntilMa5Break=trendHoldCheck.isChecked)
        val old=store.active()
        runTask("正在儲存產業範圍…"){
            if(old.isNotBlank())BtRobotWorker.pause(this,old)
            store.create(settings,if(grid!=null)"0" else required(),if(grid!=null)"0" else forbidden(),
                previous?.takeIf{!it.isNull("sourceJournalId")}?.optString("sourceJournalId"),grid)
            "產業範圍已保存；舊批次保留，按開始測試重新取得所選產業資料"
        }
    }
    private fun required()=store.session()?.optString("required","0")?:getSharedPreferences("backtest_robot",0).getString("requiredDraft","0").orEmpty()
    private fun forbidden()=store.session()?.optString("forbidden","0")?:getSharedPreferences("backtest_robot",0).getString("forbiddenDraft","0").orEmpty()
    internal fun applyRequired(key:String,forbiddenKey:String=forbidden()){
        BtRobotSpace.validateConstraints(key,forbiddenKey)
        val savedParams=store.session()?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}
        if(key==required()&&forbiddenKey==forbidden()&&!sweepCheck.isChecked&&savedParams?.dConfig==robotDConfig&&savedParams?.fConfig==robotFConfig&&savedParams?.bConfig==robotBConfig)return
        val entered=runCatching{parameters()}.getOrElse{
            Toast.makeText(this,"請修正測試參數：${it.message}",Toast.LENGTH_LONG).show();return
        }
        val range=runCatching{dates().validated()}.getOrElse{
            Toast.makeText(this,"請修正測試日期：${it.message}",Toast.LENGTH_LONG).show();return
        }
        val old=store.active();val previous=store.session()
        val settings=range.applyTo(entered.applyTo(previous?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()))
            .copy(enabledRadars=enabledRadars,dConfig=robotDConfig,eConfig=robotEConfig,fConfig=robotFConfig,bConfig=robotBConfig,exclusions=robotExclusions,holdProfitUntilMa5Break=trendHoldCheck.isChecked)
        sweepCheck.isChecked=false
        runTask("正在保存必選／排除條件並沿用快取…"){
            if(old.isNotBlank())BtRobotWorker.pause(this,old)
            val id=store.create(settings,key,forbiddenKey,previous?.takeIf{!it.isNull("sourceJournalId")}?.optString("sourceJournalId"))
            if(old.isNotBlank())BtRobotCheckpoint.cloneData(store,old,id)
            getSharedPreferences("backtest_robot",0).edit().putString("requiredDraft",key).putString("forbiddenDraft",forbiddenKey).commit()
            "必選／自由測試／排除已保存，已切換條件組合測試；舊批次保留，按開始測試執行"
        }
    }
    internal fun applyExitRules(rules:List<ExitRule>){
        ExitRules.validate(rules)
        val entered=runCatching{parameters()}.getOrElse{
            Toast.makeText(this,"請修正測試參數：${it.message}",Toast.LENGTH_LONG).show();return
        }
        val sweep=runCatching{selectedSweep()}.getOrElse{
            Toast.makeText(this,"請修正範圍：${it.message}",Toast.LENGTH_LONG).show();return
        }
        val range=runCatching{dates().validated()}.getOrElse{
            Toast.makeText(this,"請修正測試日期：${it.message}",Toast.LENGTH_LONG).show();return
        }
        val previous=store.session();val old=previous?.getString("id").orEmpty()
        val settings=range.applyTo(entered.applyTo(previous?.let{BtSettingsCodec.decode(it.getJSONObject("settings"))}?:base()))
            .copy(enabledRadars=enabledRadars,dConfig=robotDConfig,eConfig=robotEConfig,fConfig=robotFConfig,bConfig=robotBConfig,exclusions=robotExclusions,holdProfitUntilMa5Break=trendHoldCheck.isChecked)
        if(settings.exitRules==rules&&
           (previous==null||BtSettingsCodec.decode(previous.getJSONObject("settings")).holdProfitUntilMa5Break==trendHoldCheck.isChecked)&&
           (previous==null||RobotTradingParams.of(BtSettingsCodec.decode(previous.getJSONObject("settings")))==entered)&&
           (previous==null||RobotBacktestDates.of(BtSettingsCodec.decode(previous.getJSONObject("settings")))==range)&&
           BtRobotSweep.read(previous?.optJSONObject("sweep"))==sweep)return
        runTask("正在保存下車條件與新測試批次…"){
            if(old.isNotBlank())BtRobotWorker.pause(this,old)
            val id=store.create(settings.copy(exitRules=rules),
                if(sweep!=null)"0" else required(),if(sweep!=null)"0" else forbidden(),
                previous?.takeIf{!it.isNull("sourceJournalId")}?.optString("sourceJournalId"),sweep)
            if(old.isNotBlank())BtRobotCheckpoint.cloneData(store,old,id)
            com.rex.twboardingscanner.data.ExitRuleStore(this).save(StockScope.ROBOT,rules)
            "下車條件已保存；按開始測試，新批次沿用歷史資料"
        }
    }
    private fun clearZeros(){
        val id=store.active();if(id.isBlank()||busy)return
        runTask("正在清理零收益結果…"){
            val count=store.clearZero(id)
            "已清除 $count 組零收益；已測標記保留，不會重測"
        }
    }
    private fun runTask(message:String,task:()->String){
        if(busy)return;busy=true;status.text=message;startButton.isEnabled=false
        Thread{val result=runCatching(task);runOnUiThread{busy=false;if(!isFinishing&&!isDestroyed){page=0;stamp="";render();Toast.makeText(this,result.getOrElse{"操作未完成：${it.message}"},Toast.LENGTH_LONG).show()}}}.start()
    }
    private fun exportCheckpoint(){
        if(busy)return
        exportId=store.active();if(exportId.isBlank()){Toast.makeText(this,"請先建立測試",Toast.LENGTH_SHORT).show();return}
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"AI-離職神器-機器人-${exportId.take(8)}-${System.currentTimeMillis()}.zip"),731)
    }
    private fun importCheckpoint(){
        if(busy)return
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),732)
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putString("exportId",exportId);super.onSaveInstanceState(outState)}
    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data);val uri=data?.data?:return
        if(resultCode!=RESULT_OK)return
        if(requestCode==731){val id=exportId;runTask("暫停並匯出結果、斷點及快取…"){
            BtRobotWorker.pause(this,id)
            val temp=java.io.File(cacheDir,"robot-${java.util.UUID.randomUUID()}.zip")
            try{BtRobotCheckpoint.export(this,id,temp);contentResolver.openOutputStream(uri,"wt")!!.use{out->temp.inputStream().use{it.copyTo(out)}}}finally{temp.delete()}
            "已匯出 ZIP：含 CSV 結果、完整報告、斷點與歷史快取；可按繼續測試"
        }}else if(requestCode==732)runTask("正在校驗並匯入測試斷點…"){
            val old=store.active();val id=contentResolver.openInputStream(uri)!!.use{BtRobotCheckpoint.restore(this,it)}
            if(old.isNotBlank())BtRobotWorker.pause(this,old)
            if(BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).marketGuardVersion>0&&BtSettingsCodec.decode(store.session(id)!!.getJSONObject("settings")).stockPolicy==com.rex.twboardingscanner.data.StockPolicyStore(this).read()){
                BtRobotWorker.start(this,id);"已匯入獨立批次並接續未測組合"
            }else "已匯入並保留暫停；按開始將套用目前名單及大盤保護建立新批次。"
        }
    }
    private fun recover(){
        if(recovering||busy)return
        val s=store.session()?:return
        if(s.optString("state") !in setOf("RUNNING","RETRY"))return
        recovering=true
        Thread{runCatching{BtRobotWorker.recover(this,s.getString("id"),s.optString("token"))};runOnUiThread{recovering=false}}.start()
    }
    private fun radarLabel(session:JSONObject):String = BtSettingsCodec.decode(session.getJSONObject("settings")).enabledRadars
        .sortedBy{it.ordinal}.joinToString(""){it.name.take(1)}
    private fun sessions(){
        if(busy)return
        val sessions=store.sessions();if(sessions.isEmpty()){Toast.makeText(this,"尚無測試紀錄",Toast.LENGTH_SHORT).show();return}
        val names=sessions.map{ item->
            val grid=BtRobotSweep.read(item.optJSONObject("sweep"))
            "${BacktestJournalUi.time(item.getLong("created"))} · ${radarLabel(item)} ${if(grid==null)"探索" else "參數"}組\n已測 ${item.getLong("tested")} / ${grid?.total?.toString()?: (store.remaining(item.getString("id"))+BigInteger.valueOf(item.getLong("tested"))).toString()} 組\n"+
                (if(item.has("bestProfit"))"已測最佳 ${signed(item.getDouble("bestProfit"))} 元"else"尚無結果")+
                (if(item.optString("state")=="DONE")" · 已完成"else"")
        }
        MaterialAlertDialogBuilder(this).setTitle("選擇測試批次").setItems(names.toTypedArray()){_,i->
            if(store.active()!=sessions[i].getString("id"))BtRobotWorker.pause(this,store.active())
            store.activate(sessions[i].getString("id"));stamp="";render()
        }.setNegativeButton("關閉",null).show()
    }
}
