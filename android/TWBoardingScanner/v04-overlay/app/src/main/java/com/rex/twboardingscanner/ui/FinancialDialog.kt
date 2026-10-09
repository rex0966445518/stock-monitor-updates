package com.rex.twboardingscanner.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rex.twboardingscanner.data.FinancialDataProvider
import com.rex.twboardingscanner.domain.*
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.Future

class FinancialDialog(context:Context,private val provider:FinancialDataProvider,private val code:String,
    private val name:String,private val initial:FinancialReport?):Dialog(context) {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var job:Future<*>?=null
    private var closed=false
    private lateinit var panel:FinancialPanelView
    private lateinit var meta:TextView
    private lateinit var detail:TextView
    private lateinit var progress:ProgressBar
    private lateinit var refresh:com.google.android.material.button.MaterialButton
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val root=NeonUi.vertical(context).apply {
            background=NeonUi.panel(context,NeonUi.cyan)
            setPadding(NeonUi.dp(context,16),NeonUi.dp(context,12),NeonUi.dp(context,16),NeonUi.dp(context,12))
        }
        val top=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val heading=NeonUi.vertical(context).apply {
            addView(NeonUi.label(context,"FINANCIAL CHECK  /  $code",10f,NeonUi.cyan,true))
            addView(NeonUi.label(context,name,21f,NeonUi.ink,true).apply { setPadding(0,NeonUi.dp(context,5),0,NeonUi.dp(context,3)) })
            addView(NeonUi.label(context,"公司財務查核",12f))
        }
        top.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(NeonUi.button(context,"×",NeonUi.muted){dismiss()}.apply { contentDescription="關閉財務查核" },LinearLayout.LayoutParams(NeonUi.dp(context,48),NeonUi.dp(context,48)))
        root.addView(top)
        root.addView(NeonUi.gap(context,10))
        progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate=true;indeterminateTintList=android.content.res.ColorStateList.valueOf(NeonUi.cyan);visibility=View.GONE
        }
        root.addView(progress,LinearLayout.LayoutParams(-1,NeonUi.dp(context,3)))
        val body=NeonUi.vertical(context)
        val scroll=ScrollView(context).apply { isFillViewport=true;clipToPadding=false;addView(body) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        panel=FinancialPanelView(context)
        body.addView(panel)
        body.addView(NeonUi.gap(context,14))
        meta=NeonUi.label(context,"",11f)
        body.addView(meta)
        body.addView(NeonUi.gap(context,12))
        body.addView(NeonUi.label(context,"查看原始報表",12f,NeonUi.ink,true))
        body.addView(NeonUi.gap(context,8))
        body.addView(NeonUi.row(context,listOf("EPS ↗" to "每股盈餘","現金流 ↗" to "現金流量表","本業 ↗" to "利潤比率").map { (label,topic) ->
            NeonUi.button(context,label){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FinancialDataProvider.url(code,topic))))}.onFailure{Toast.makeText(context,"無法開啟瀏覽器",Toast.LENGTH_SHORT).show()}}
        }))
        body.addView(NeonUi.gap(context,12))
        detail=NeonUi.label(context,"",12f).apply { visibility=View.GONE;setPadding(NeonUi.dp(context,8),NeonUi.dp(context,8),NeonUi.dp(context,8),NeonUi.dp(context,12));setLineSpacing(NeonUi.dp(context,4).toFloat(),1f);setTextIsSelectable(true) }
        val more=NeonUi.button(context,"資料來源與查核說明  ⌄",NeonUi.muted){}
        more.setOnClickListener { detail.visibility=if(detail.visibility==View.VISIBLE)View.GONE else View.VISIBLE;more.text=if(detail.visibility==View.VISIBLE)"收起查核說明  ⌃" else "資料來源與查核說明  ⌄" }
        body.addView(more,LinearLayout.LayoutParams(-1,-2));body.addView(detail)
        root.addView(NeonUi.gap(context,10))
        refresh=NeonUi.button(context,"↻  更新財報",NeonUi.pink){reload()}
        root.addView(refresh,LinearLayout.LayoutParams(-1,-2))
        root.addView(NeonUi.label(context,"查核後重新掃描，可套用最新財務結果",10f).apply { gravity=Gravity.CENTER;setPadding(0,NeonUi.dp(context,7),0,0) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(NeonUi.dp(context,16)+bars.left,NeonUi.dp(context,12)+bars.top,NeonUi.dp(context,16)+bars.right,NeonUi.dp(context,12)+bars.bottom)
            insets
        }
        bind(initial)
    }
    override fun onStart() {
        super.onStart()
        window?.setLayout(context.resources.displayMetrics.widthPixels.coerceAtMost(NeonUi.dp(context,680)),android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private fun bind(report:FinancialReport?) {
        val today=LocalDate.now(RuleMetrics.TAIPEI)
        panel.bind(report,today,true)
        val stamp=report?.let { java.text.SimpleDateFormat("MM/dd HH:mm",java.util.Locale.TAIWAN).apply { timeZone=java.util.TimeZone.getTimeZone("Asia/Taipei") }.format(java.util.Date(it.fetchedAt)) }
        meta.text="來源 HiStock  ·  " + (stamp?.let{"查核／快取 $it"}?:"尚未讀取") +
            if(report?.messages?.isNotEmpty()==true) "\n! 來源尚有待查核項目，可展開下方說明" else ""
        detail.text=(report?.messages?.takeIf{it.isNotEmpty()}?.joinToString("\n",postfix="\n\n")?:"") +
            "資料快取 24 小時。顯示的是各指標最新可用期間，期間可能不同。\n\nEPS 為正不等於現金流為正；營業利益率為正亦不能排除一次性業外收益。\n\n現金流原站未明確標示單季／累計及單位，本版只查核最新列示值正負，不加總成年或自由現金流。金融業需另行解讀。\n\n勾選的條件缺資料或未通過，會阻擋入選；未勾選僅供觀察。查核結果不代表未來獲利。"
    }
    private fun reload() {
        refresh.isEnabled=false;refresh.text="查核中…";progress.visibility=View.VISIBLE
        job=worker.submit {
            val report=runCatching{provider.load(code)}.getOrElse{FinancialReport(messages=listOf("讀取失敗，請稍後再試"),fetchedAt=System.currentTimeMillis())}
            handler.post { if(!closed&&isShowing){bind(report);refresh.isEnabled=true;refresh.text="↻  更新財報";progress.visibility=View.GONE} }
        }
    }
    override fun dismiss() { closed=true;job?.cancel(true);worker.shutdownNow();handler.removeCallbacksAndMessages(null);super.dismiss() }
}
