package com.rex.twboardingscanner.ui
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import com.rex.twboardingscanner.domain.*

class ClosingAuctionView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):LinearLayout(context,attrs) {
    private val title=NeonUi.label(context,"13:30 收盤撮合量",13f,NeonUi.muted)
    private val value=NeonUi.label(context,"待查核",32f,NeonUi.muted,true)
    private val detail=NeonUi.label(context,"",11f,NeonUi.muted)
    init {orientation=VERTICAL;setPadding(NeonUi.dp(context,12),NeonUi.dp(context,10),NeonUi.dp(context,12),NeonUi.dp(context,10));background=NeonUi.panel(context);addView(title);addView(value);addView(detail)}
    fun bind(auction:ClosingAuction?) {
        val a=auction?:ClosingAuction()
        title.text=if(a.time=="13:33:00")"13:33 收盤撮合量（延後撮合）" else "13:30 收盤撮合量"
        value.text=if(a.lots==null&&a.note.startsWith("尚未"))"待收盤" else a.display
        value.setTextColor(when(a.direction){AuctionDirection.UP->Color.rgb(255,73,108);AuctionDirection.DOWN->Color.rgb(47,209,138);else->NeonUi.muted})
        detail.text=listOfNotNull(a.date.takeIf{it.isNotBlank()},a.note).joinToString(" · ")+if(a.lots!=null)"\n撮合量來源：證交所 MIS；前價參考：Yahoo同日成交彙整。\n正負號表示價格方向，並非負成交量。" else ""
        contentDescription="${title.text} ${value.text} ${detail.text}"
    }
}
