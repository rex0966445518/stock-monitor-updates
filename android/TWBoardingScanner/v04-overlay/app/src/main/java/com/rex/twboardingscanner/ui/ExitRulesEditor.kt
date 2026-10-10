package com.rex.twboardingscanner.ui

import android.content.Context
import android.text.InputType
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.*

internal object ExitRulesEditor {
    private data class Fields(val days:EditText,val compare:Spinner,val loss:EditText,val macd:Spinner,val ma:EditText)
    fun show(c:Context,title:String,rules:List<ExitRule>,save:(List<ExitRule>)->Unit):androidx.appcompat.app.AlertDialog {
        val form=Form(c,if(rules.isEmpty())ExitRules.example() else rules)
        lateinit var dialog:androidx.appcompat.app.AlertDialog
        val viewport=NeonUi.vertical(c).apply{
            setPadding(NeonUi.dp(c,16),NeonUi.dp(c,20),NeonUi.dp(c,16),NeonUi.dp(c,16))
            addView(NeonUi.label(c,"下車賣出條件",23f,NeonUi.ink,true))
            addView(NeonUi.gap(c,6));addView(NeonUi.label(c,"$title · 最多 5 組，空白不限制",12f))
            addView(NeonUi.gap(c,12))
            addView(ScrollView(c).apply{addView(form);isFillViewport=false},LinearLayout.LayoutParams(-1,0,1f))
            addView(NeonUi.gap(c,12))
            addView(NeonUi.row(c,listOf(
                NeonUi.button(c,"取消",NeonUi.muted){dialog.dismiss()},
                NeonUi.primary(c,"儲存條件"){
                    runCatching{val result=form.read();save(result)}.onSuccess{dialog.dismiss();Toast.makeText(c,"下車條件已儲存",Toast.LENGTH_SHORT).show()}
                        .onFailure{Toast.makeText(c,it.message?:"請檢查欄位",Toast.LENGTH_LONG).show()}
                }.apply{tag="exit-save"}
            )))
        }
        dialog=MaterialAlertDialogBuilder(c).setBackground(NeonUi.panel(c)).setView(viewport).create()
        dialog.show()
        dialog.window?.apply{
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,(c.resources.displayMetrics.heightPixels*.9).toInt())
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog
    }
    internal class Form(c:Context,rules:List<ExitRule>):LinearLayout(c) {
        private val fields=mutableListOf<Fields>()
        init {
            orientation=VERTICAL;setPadding(NeonUi.dp(c,16),NeonUi.dp(c,8),NeonUi.dp(c,16),NeonUi.dp(c,16));setBackgroundColor(NeonUi.canvas)
            addView(NeonUi.label(c,"同組已填項目全部成立才賣出；任一組成立即可。空白不限制，全空白不啟用。僅限虧損持股，虧損以扣買賣費稅後的正數幅度計算。",12f))
            addView(NeonUi.label(c,"比較方式＋百分比需同時填寫；只填一項時，整個虧損幅度條件略過。天數為日曆日；MACD 指 DIF 較前日升降；跌破指收盤低於均線。",12f,NeonUi.cyan))
            addView(NeonUi.label(c,"回測／機器人：完成日線判斷，下一個可交易日開盤模擬賣出。主頁：套用到模擬持倉；原有停利、保本與其他出場規則仍有效。資料不足的已填指標不會通過。",12f))
            addView(NeonUi.label(c,"例如虧損小於 3%：虧損 2% 符合，虧損 5% 不符合。",12f,NeonUi.amber))
            NeonUi.group(this,getChildAt(0),getChildAt(childCount-1),"填寫與判斷說明","空白項目不限制；同組全部符合，任一組成立即賣出。","exit-help")
            addView(NeonUi.row(c,listOf(NeonUi.button(c,"清空全部"){bind(List(5){ExitRule()})},NeonUi.button(c,"帶入第 1 組範例"){bindRow(0,ExitRules.example()[0])})))
            repeat(5){i->
                addView(NeonUi.gap(c,12))
                val card=NeonUi.vertical(c).apply{background=NeonUi.panel(c,NeonUi.cyan);setPadding(NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12),NeonUi.dp(c,12));tag="exit-card-$i"}
                card.addView(NeonUi.label(c,"條件 ${i+1} · 認賠賣出",16f,NeonUi.ink,true))
                fun field(label:String,key:String,decimal:Boolean=false):EditText {
                    card.addView(NeonUi.label(c,label,12f))
                    return NeonUi.field(EditText(c)).apply{tag="exit-$i-$key";hint="留白不限制";setSingleLine();setTextColor(NeonUi.ink);setHintTextColor(NeonUi.muted);inputType=InputType.TYPE_CLASS_NUMBER or if(decimal)InputType.TYPE_NUMBER_FLAG_DECIMAL else 0;minHeight=NeonUi.dp(c,48)}.also{card.addView(it)}
                }
                fun select(label:String,key:String,items:List<String>):Spinner {
                    card.addView(NeonUi.label(c,label,12f))
                    return Spinner(c).apply{tag="exit-$i-$key";background=NeonUi.panel(c);adapter=object:ArrayAdapter<String>(c,android.R.layout.simple_spinner_item,items){
                        override fun getView(p:Int,v:android.view.View?,g:android.view.ViewGroup)=super.getView(p,v,g).also{(it as TextView).setTextColor(NeonUi.ink)}
                        override fun getDropDownView(p:Int,v:android.view.View?,g:android.view.ViewGroup)=super.getDropDownView(p,v,g).also{(it as TextView).setTextColor(NeonUi.ink);it.setBackgroundColor(NeonUi.canvas);it.setPadding(20,20,20,20)}
                    }.also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}}.also{card.addView(it,LayoutParams(-1,NeonUi.dp(c,48)))}
                }
                val days=field("留倉超過（天）","days")
                val compare=select("虧損幅度比較","compare",listOf("留白／不限制")+LossCompare.entries.map{it.label})
                val loss=field("虧損幅度（%）","loss",true)
                val macd=select("且 MACD DIF","macd",listOf("留白／不限制")+ExitMacd.entries.map{it.label})
                val ma=field("且跌破（日均線）","ma")
                fields+=Fields(days,compare,loss,macd,ma)
                card.addView(NeonUi.button(c,"清空這一組",NeonUi.muted){bindRow(i,ExitRule())});addView(card)
                card.background=null;card.setPadding(0,NeonUi.dp(c,8),0,0)
                card.removeViewAt(0)
                NeonUi.group(this,card,card,"第 ${i+1} 組 · 認賠賣出",key="",expanded=i==0||rules.getOrNull(i)?.active==true)
            }
            bind(rules)
        }
        private fun bindRow(i:Int,r:ExitRule){val f=fields[i];f.days.setText(r.days?.toString().orEmpty());f.compare.setSelection(r.compare?.ordinal?.plus(1)?:0);f.loss.setText(r.lossPct?.let{java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()}.orEmpty());f.macd.setSelection(r.macd?.ordinal?.plus(1)?:0);f.ma.setText(r.maDays?.toString().orEmpty())}
        fun bind(rules:List<ExitRule>){fields.indices.forEach{bindRow(it,rules.getOrNull(it)?:ExitRule())}}
        fun read():List<ExitRule> = fields.mapIndexed{i,f->
            fun integer(e:EditText):Int? {val raw=e.text.toString().trim();if(raw.isBlank())return null;return raw.toIntOrNull()?:error("第 ${i+1} 組請填有效整數")}
            val raw=f.loss.text.toString().trim();val loss=if(raw.isBlank())null else raw.toDoubleOrNull()?:error("第 ${i+1} 組虧損幅度無效")
            ExitRule(integer(f.days),LossCompare.entries.getOrNull(f.compare.selectedItemPosition-1),loss,ExitMacd.entries.getOrNull(f.macd.selectedItemPosition-1),integer(f.ma)).also{it.validate()}
        }.also(ExitRules::validate)
    }
}
