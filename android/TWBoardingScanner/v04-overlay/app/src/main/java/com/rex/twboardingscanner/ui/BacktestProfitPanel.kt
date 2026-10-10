package com.rex.twboardingscanner.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.rex.twboardingscanner.backtest.BacktestHoldingLedger
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Two tappable summary tiles; every archived lot is accessible, without a 30-row cutoff. */
internal class BacktestProfitPanel(c:Context,trades:JSONArray,holdings:JSONArray,realized:Double,unrealized:Double,closed:Int,cutoff:String):LinearLayout(c){
    private fun signed(v:Double)=String.format(Locale.TAIWAN,"%+,.0f",v)
    private fun tint(v:Double)=if(v<0)NeonUi.mint else NeonUi.pink
    init {
        orientation=VERTICAL
        val sold=(0 until trades.length()).map{trades.getJSONObject(it)}.filter{it.optString("side")=="SELL"}.sortedByDescending{it.optString("date")}
        val rawHeld=BacktestHoldingLedger.rows(holdings)
        val held=(0 until rawHeld.length()).map{rawHeld.getJSONObject(it)}.sortedWith(compareBy({it.optString("code")},{it.optString("entryDate")}))
        val industries=com.rex.twboardingscanner.data.StockDirectory(c).industryLabels()
        val soldTile=NeonUi.tile(c,"已實現獲利",signed(realized),"已賣出 $closed 張 · 展開 ▾",tint(realized)).apply{tag="realized-profit-toggle"}
        val heldTile=NeonUi.tile(c,"未實現損益",signed(unrealized),"持倉 ${holdings.length()} 張 · 展開 ▾",tint(unrealized)).apply{tag="unrealized-profit-toggle"}
        addView(NeonUi.row(c,listOf(soldTile,heldTile)))
        val host=NeonUi.vertical(c).apply{tag="profit-breakdown-details";visibility=View.GONE};addView(host)
        var selected:String?=null;var shown=20
        fun render(){
            host.removeAllViews();host.visibility=if(selected==null)View.GONE else View.VISIBLE
            (soldTile.getChildAt(2) as TextView).text="已賣出 $closed 張 · ${if(selected=="sold")"收合 ▴" else "展開 ▾"}"
            (heldTile.getChildAt(2) as TextView).text="持倉 ${holdings.length()} 張 · ${if(selected=="held")"收合 ▴" else "展開 ▾"}"
            soldTile.isSelected=selected=="sold";heldTile.isSelected=selected=="held"
            soldTile.contentDescription="已實現獲利 ${signed(realized)} 元，${if(selected=="sold")"點擊收合" else "點擊展開所有已賣出明細"}"
            heldTile.contentDescription="未實現損益 ${signed(unrealized)} 元，${if(selected=="held")"點擊收合" else "點擊展開所有留倉明細"}"
            if(selected==null)return
            val selling=selected=="sold";val rows=if(selling)sold else held
            host.addView(NeonUi.gap(c,12))
            host.addView(NeonUi.label(c,if(selling)"已實現 · 完整賣出清單" else "未實現 · 完整留倉清單",19f,NeonUi.ink,true))
            host.addView(NeonUi.label(c,"${rows.size} 筆／${rows.map{it.optString("code")}.distinct().size} 檔 · 同股按買入批次分列",12f,NeonUi.cyan))
            host.addView(NeonUi.label(c,if(selling)"包含獲利及虧損交易；賣出時漲跌以模擬成交價對前收計算。" else "估值截至 $cutoff，使用該次回測保存的價格；包含獲利及虧損持倉。",11f))
            if(rows.isEmpty())host.addView(NeonUi.label(c,if((if(selling)closed else holdings.length())>0)"舊日誌缺少逐筆資料" else if(selling)"本次尚無已賣出交易" else "截止日沒有留倉",14f,NeonUi.muted))
            else{
                val key=if(selling)"netProfit" else "unrealized"
                val complete=rows.all{!it.isNull(key)&&it.optDouble(key,Double.NaN).isFinite()}
                val total=if(selling)realized else unrealized
                if(!complete||kotlin.math.abs(rows.sumOf{it.optDouble(key,0.0)}-total)>.5)host.addView(NeonUi.label(c,"部分舊明細缺資料或與總額不一致，上方保留原日誌總額。",11f,NeonUi.amber))
            }
            rows.take(shown).forEach{row->host.addView(NeonUi.gap(c,8));host.addView(BacktestProfitCard(c,row,selling,cutoff,industries[row.optString("code")]?:"待分類"))}
            if(shown<rows.size)host.addView(NeonUi.button(c,"載入更多（已顯示 $shown／${rows.size} 筆）"){shown+=20;render()}.apply{tag="profit-load-more"})
            else if(rows.isNotEmpty())host.addView(NeonUi.label(c,"已顯示全部 ${rows.size} 筆",12f,NeonUi.cyan))
            host.addView(NeonUi.button(c,"收合清單",NeonUi.muted){selected=null;render()}.apply{tag="profit-collapse"})
            host.addView(NeonUi.gap(c,12))
        }
        fun toggle(which:String){selected=if(selected==which)null else which;shown=20;render()}
        soldTile.isFocusable=true;heldTile.isFocusable=true
        soldTile.setOnClickListener{toggle("sold")};heldTile.setOnClickListener{toggle("held")}
        render()
    }
}
