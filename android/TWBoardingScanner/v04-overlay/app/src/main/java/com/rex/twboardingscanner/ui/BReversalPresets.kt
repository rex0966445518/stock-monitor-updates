package com.rex.twboardingscanner.ui
import android.content.Context
import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.BPattern
internal object BReversalPresets{
    fun add(c:Context,parent:LinearLayout,pick:(Set<String>)->Unit){
        parent.addView(NeonUi.label(c,"B 優化預設：保留目前數值，替換本區勾選；原始舊條件仍可自行勾選。缺資料不通過。",12f,NeonUi.amber))
        parent.addView(NeonUi.row(c,listOf(
            NeonUi.button(c,"早期起轉"){pick(BPattern.EARLY)}.apply{tag="b-preset-early"},
            NeonUi.button(c,"放量突破確認",NeonUi.mint){pick(BPattern.CONFIRMED)}.apply{tag="b-preset-confirmed"}
        )))
        parent.addView(NeonUi.label(c,"早期：深跌＋不破底＋MACD連升＋站上5日線＋近期反轉事件任一。確認：再要求突破前高且放量。預設參數待回測；比較時同時看含留倉損益與回撤，未保證勝率提高。",11f))
    }
}
