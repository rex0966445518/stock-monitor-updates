package com.rex.twboardingscanner.ui

import android.content.Context
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.*

/** Dialog-local values: no live settings change until the containing form is saved. */
internal class RuleNumberFields(private val c:Context,d:DPatternConfig,f:FPatternConfig){
    private val values=linkedMapOf<String,String>()
    private val inputs=mutableMapOf<String,MutableList<EditText>>()
    init{setD(d);setF(f)}
    private fun put(key:String,value:Any){
        val raw=value.toString();values[key]=raw
        inputs[key].orEmpty().forEach{if(it.text.toString()!=raw)it.setText(raw)}
    }
    fun setD(v:DPatternConfig){put("d-lookback",v.lookback);put("d-limit",v.minimumLimitHits);put("d-green",v.bullishBars);put("d-volumeDays",v.volumeDays);put("d-base",v.volumeBaseDays);put("d-multiple",v.volumeMultiple)}
    fun setF(v:FPatternConfig){put("f-days",v.days);put("f-pct",v.volumePct);put("f-averageDays",v.averageDays);put("f-averagePct",v.averagePct);put("f-lots",v.minLots);put("f-ma",v.maDays);put("f-gainDays",v.gainDays);put("f-gain",v.maxGainPct)}
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
        if(type==RadarType.D_MAIN_FORCE)when(id){
            DPattern.LIMIT->{field("最近交易日（4～120，共用範圍）","d-lookback");field("至少漲停次數（1～觀察日數）","d-limit")}
            DPattern.GREEN->{field("最近交易日（與漲停／缺口共用）","d-lookback");field("連續陽線根數（2～觀察日數）","d-green")}
            DPattern.GAP->field("最近交易日（與漲停／連陽共用）","d-lookback")
            DPattern.VOLUME->{field("最近連續放量日數（1～10）","d-volumeDays");field("放量前均量天數（5～120）","d-base");field("每日至少均量倍數（1～20）","d-multiple",true)}
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
