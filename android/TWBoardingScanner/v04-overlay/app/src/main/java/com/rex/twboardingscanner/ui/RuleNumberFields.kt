package com.rex.twboardingscanner.ui

import android.content.Context
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.*

/** Dialog-local values: no live settings change until the containing form is saved. */
internal class RuleNumberFields(private val c:Context,d:DPatternConfig,f:FPatternConfig,b:BPatternConfig=BPatternConfig.DEFAULT,g:GPatternConfig=GPatternConfig.DEFAULT){
    private val values=linkedMapOf<String,String>()
    private val inputs=mutableMapOf<String,MutableList<EditText>>()
    init{setD(d);setF(f);setB(b);setG(g)}
    private var gBase:GPatternConfig=g
    private fun put(key:String,value:Any){
        val raw=value.toString();values[key]=raw
        inputs[key].orEmpty().forEach{if(it.text.toString()!=raw)it.setText(raw)}
    }
    fun setB(v:BPatternConfig){put("b-depthDays",v.depthDays);put("b-depthPct",v.depthPct);put("b-stopDays",v.stopDays);put("b-supportDays",v.supportDays);put("b-momentumDays",v.momentumDays);put("b-eventDays",v.eventDays);put("b-rsiLookback",v.rsiLookback);put("b-rsiThreshold",v.rsiThreshold);put("b-breakoutDays",v.breakoutDays);put("b-volumeDays",v.volumeDays);put("b-volumeMultiple",v.volumeMultiple)}
    fun readB()=BPatternConfig(int("b-depthDays"),decimal("b-depthPct"),int("b-stopDays"),int("b-supportDays"),int("b-momentumDays"),int("b-eventDays"),int("b-rsiLookback"),decimal("b-rsiThreshold"),int("b-breakoutDays"),int("b-volumeDays"),decimal("b-volumeMultiple")).validated()
    fun setD(v:DPatternConfig){put("d-lookback",v.lookback);put("d-limit",v.minimumLimitHits);put("d-green",v.bullishBars);put("d-volumeDays",v.volumeDays);put("d-base",v.volumeBaseDays);put("d-multiple",v.volumeMultiple)}
    fun setF(v:FPatternConfig){put("f-days",v.days);put("f-pct",v.volumePct);put("f-averageDays",v.averageDays);put("f-averagePct",v.averagePct);put("f-lots",v.minLots);put("f-ma",v.maDays);put("f-gainDays",v.gainDays);put("f-gain",v.maxGainPct)}
    fun setG(v:GPatternConfig){
        gBase=v
        put("g-eventDays",v.eventDays);put("g-eventCount",v.eventCount);put("g-eventGain",v.eventGainPct)
        put("g-pullbackDays",v.pullbackDays);put("g-touchAbove",v.touchAbovePct);put("g-touchBelow",v.touchBelowPct)
        put("g-maxAbove",v.maxAbovePct);put("g-volumeDays",v.volumeDays);put("g-volumeMultiple",v.volumeMultiple)
        put("g-maRiseDays",v.maRiseDays);put("g-rsi",v.minRsi);put("g-price",v.minPrice);put("g-lots",v.minLots)
    }
    fun readG():GPatternConfig=gBase.copy(
        eventDays=int("g-eventDays"),eventCount=int("g-eventCount"),eventGainPct=decimal("g-eventGain"),
        pullbackDays=int("g-pullbackDays"),touchAbovePct=decimal("g-touchAbove"),
        touchBelowPct=decimal("g-touchBelow"),maxAbovePct=decimal("g-maxAbove"),
        volumeDays=int("g-volumeDays"),volumeMultiple=decimal("g-volumeMultiple"),
        maRiseDays=int("g-maRiseDays"),minRsi=decimal("g-rsi"),
        minPrice=decimal("g-price"),minLots=int("g-lots")
    ).validated()
    fun editG(){runCatching{readG()}.onSuccess{GPatternEditor.show(c,it,::setG)}.onFailure{android.widget.Toast.makeText(c,it.message,android.widget.Toast.LENGTH_LONG).show()}}
    fun editD(){runCatching{readD()}.onSuccess{DPatternEditor.show(c,it,::setD)}.onFailure{android.widget.Toast.makeText(c,it.message,android.widget.Toast.LENGTH_LONG).show()}}
    fun editF(){runCatching{readF()}.onSuccess{FPatternEditor.show(c,it,::setF)}.onFailure{android.widget.Toast.makeText(c,it.message,android.widget.Toast.LENGTH_LONG).show()}}
    private fun number(key:String):String=values.getValue(key).trim().also{require(it.isNotEmpty()){ "數字欄位不可留白；請填數值或取消此次修改" }}
    private fun int(key:String)=number(key).toIntOrNull()?:error("請填有效整數")
    private fun decimal(key:String)=number(key).toDoubleOrNull()?:error("請填有效數字")
    fun readD()=DPatternConfig(int("d-lookback"),int("d-limit"),int("d-green"),int("d-volumeDays"),int("d-base"),decimal("d-multiple")).validated()
    fun readF()=FPatternConfig(int("f-days"),decimal("f-pct"),int("f-averageDays"),decimal("f-averagePct"),int("f-lots"),int("f-ma"),int("f-gainDays"),decimal("f-gain")).validated()
    fun add(parent:LinearLayout,type:RadarType,id:String){
        fun field(label:String,key:String,decimal:Boolean=false){
            val row=LinearLayout(c).apply{gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(NeonUi.dp(c,12),0,0,NeonUi.dp(c,6))}
            row.addView(NeonUi.label(c,label,12f,NeonUi.ink),LinearLayout.LayoutParams(0,-2,1f))
            val input=NeonUi.field(EditText(c)).apply{
                tag="rule-number-$id-$key";contentDescription=label;setSingleLine();setSelectAllOnFocus(true)
                inputType=InputType.TYPE_CLASS_NUMBER or if(decimal)InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                setText(values.getValue(key));setTextColor(NeonUi.radarColor(type))
            }
            inputs.getOrPut(key){mutableListOf()}.add(input)
            input.addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(values[key]!=s.toString())put(key,s.toString())}
                override fun afterTextChanged(s:Editable?){}
            })
            row.addView(input,LinearLayout.LayoutParams(NeonUi.dp(c,88),-2).apply{marginStart=NeonUi.dp(c,8)})
            parent.addView(row)
        }
        if(type==RadarType.B_DEEP_REVERSAL)when(id){
            BPattern.DEPTH->{field("深跌觀察交易日（20～504）","b-depthDays");field("距最高收盤價跌幅（%，5～90）","b-depthPct",true)}
            BPattern.STOP->{field("最近不破底日數（1～10）","b-stopDays");field("此前支撐觀察日（5～120）","b-supportDays")}
            BPattern.MOMENTUM->field("DIF與柱狀體連續升高日數（1～5）","b-momentumDays")
            BPattern.RSI,BPattern.EVENT->{field("近期反轉事件範圍（1～10日）","b-eventDays");field("RSI此前超賣觀察日（2～60）","b-rsiLookback");field("RSI向上突破門檻（30～50）","b-rsiThreshold",true)}
            BPattern.BREAKOUT->{field("突破此前最高價日數（3～60）","b-breakoutDays");field("此前均量觀察日（5～120）","b-volumeDays");field("突破成交量倍數（1～5）","b-volumeMultiple",true)}
        }
        if(type==RadarType.D_MAIN_FORCE)when(id){
            DPattern.LIMIT->{field("最近交易日（4～120，共用範圍）","d-lookback");field("至少漲停次數（1～觀察日數）","d-limit")}
            DPattern.GREEN->{field("最近交易日（與漲停／缺口共用）","d-lookback");field("連續陽線根數（2～觀察日數）","d-green")}
            DPattern.GAP->field("最近交易日（與漲停／連陽共用）","d-lookback")
            DPattern.VOLUME->{field("最近連續放量日數（1～10）","d-volumeDays");field("放量前均量天數（5～120）","d-base");field("每日至少均量倍數（1～20）","d-multiple",true)}
        }
        if(type==RadarType.G_MA5_WAVE)when(id){
            GPattern.EVENT->{field("近期交易日（2～120）","g-eventDays");field("至少大陽線次數","g-eventCount");field("大陽線漲幅 ≥（%）","g-eventGain",true)}
            GPattern.PULLBACK->{field("大陽線後回踩交易日數","g-pullbackDays");field("低點最多高於5MA（%）","g-touchAbove",true);field("低點可跌破5MA（%）","g-touchBelow",true);field("收盤最多高於5MA（%）","g-maxAbove",true)}
            GPattern.EVENT_VOLUME->{field("事件前均量交易日","g-volumeDays");field("大陽線當日量比 ≥","g-volumeMultiple",true)}
            GPattern.MA_RISE->field("5MA連續上升日數","g-maRiseDays")
            GPattern.RSI_MIN->field("RSI最低門檻","g-rsi",true)
            GPattern.PRICE->field("最低股價（元）","g-price",true)
            GPattern.LOTS->field("最低成交量（張）","g-lots")
        }
        if(type==RadarType.F_VOLUME_CONTRACTION)when(id){
            FPattern.EVENT->{field("連續符合交易日（1～60）","f-days");field("每日量 ≤ 前日量（%，0～100之間）","f-pct",true)}
            "f_average"->{field("前幾日均量（不含當日，1～120）","f-averageDays");field("當日量 ≤ 均量（%，大於0至100）","f-averagePct",true)}
            "f_min_volume"->field("最低成交量（張）","f-lots")
            "f_ma"->field("向上均線天數（1～120）","f-ma")
            "f_gain"->{field("累計漲幅觀察交易日（1～120）","f-gainDays");field("累計漲幅上限（%）","f-gain",true)}
        }
    }
}
