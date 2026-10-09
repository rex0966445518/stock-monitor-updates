package com.rex.twboardingscanner.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.data.FinancialDataProvider
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinancialLayoutTest {
    private val today=LocalDate.now(RuleMetrics.TAIPEI)
    private fun fixture():FinancialReport {
        val current=today.year*4+(today.monthValue-1)/3
        val eps=(current-3..current).mapIndexed { i,k -> QuarterEps((k-1)/4,(k-1)%4+1,2.53+i*0.18) }
        val last=eps.last()
        return FinancialReport(eps=eps,operatingCash=listOf(PeriodValue(last.year,last.quarter,-100.0)),
            operatingMargin=listOf(PeriodValue(last.year,last.quarter,31.35)),fetchedAt=System.currentTimeMillis())
    }
    private fun measure(view:View,width:Int,height:Int?=null) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height?:0,if(height==null)View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
    }
    private fun screenshot(view:View,name:String) {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file=File("build/ui-previews/$name.png");file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun assertLabelsFit(view:View) {
        if(view.visibility!=View.VISIBLE)return
        if(view is TextView && view.text.isNotEmpty() && view.layout!=null) {
            val layout=view.layout
            assertTrue("Text vertically clipped: ${view.text}",layout.height<=view.height-view.compoundPaddingTop-view.compoundPaddingBottom+2)
        }
        if(view is ViewGroup)for(i in 0 until view.childCount)assertLabelsFit(view.getChildAt(i))
    }
    @Test fun mainScreenFiltersFitAndRespondToSelection() {
        val c=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_TWBoardingScanner)
        val b=com.rex.twboardingscanner.databinding.ActivityMainBinding.inflate(android.view.LayoutInflater.from(c))
        b.progressTitle.text="全市場掃描完成"
        b.progressCount.text="已處理 1950 / 1950 檔（100%）"
        b.etaText.text="掃描完成"
        b.progressStats.text="已分析 1950｜排除 0｜資料不足 0｜失敗 0"
        b.scanProgress.max=1950;b.scanProgress.progress=1950
        b.resultFilters.update(listOf(8,0,4,125,5400),0)
        var selected=-1
        b.resultFilters.onSelected={selected=it;b.resultFilters.update(listOf(8,0,4,125,5400),it)}
        val firstRow=b.resultFilters.getChildAt(0) as ViewGroup
        firstRow.getChildAt(2).performClick()
        assertEquals(2,selected);assertTrue(firstRow.getChildAt(2).isSelected)
        assertFalse(firstRow.getChildAt(0).isSelected)
        measure(b.root,NeonUi.dp(c,360),NeonUi.dp(c,800));assertLabelsFit(b.root)
        screenshot(b.root,"main-screen-360")
        RuntimeEnvironment.setFontScale(1.3f)
        measure(b.root,NeonUi.dp(c,360),NeonUi.dp(c,800));assertLabelsFit(b.root)
    }
    @Test fun compactCardAndExpandedPanelRenderWithoutClippedText() {
        val c=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_TWBoardingScanner)
        val width=NeonUi.dp(c,336)
        val panel=FinancialPanelView(c);panel.bind(fixture(),today,true)
        measure(panel,width);assertLabelsFit(panel);screenshot(panel,"financial-panel-360")
        val stock=MarketStock("6146","版面測試公司",Market.TWSE,StockSector.ELECTRONICS,100.0,105.0,98.0,103.0,3.0,1383,null,1.0,financials=fixture())
        val bars=(0..79).map { i->val close=90.0+i*0.2; DailyBar(today.minusDays((80-i).toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close-0.2,close+0.8,close-1,close,if(i==79)1383060L else 160000L+i*100) }
        val signal=ScoringEngine().evaluateA(TechnicalCalculator().build(stock,bars),setOf("price","volume"))
        val adapter=SignalAdapter();adapter.submit(listOf(signal))
        val holder=adapter.onCreateViewHolder(LinearLayout(c),0);adapter.onBindViewHolder(holder,0)
        measure(holder.itemView,width);assertLabelsFit(holder.itemView);screenshot(holder.itemView,"stock-card-360")
        panel.bind(null,today,true);measure(panel,width);assertLabelsFit(panel);screenshot(panel,"financial-pending-360")
    }
    @Test @Config(qualifiers="zh-rTW-w412dp-h915dp-xhdpi") fun wideLayoutAndLargerFontRemainReadable() {
        RuntimeEnvironment.setFontScale(1.3f)
        val c=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_TWBoardingScanner)
        val p=FinancialPanelView(c);p.bind(fixture(),today)
        measure(p,NeonUi.dp(c,388));assertLabelsFit(p);screenshot(p,"compact-finance-412-large-font")
    }
    @Test fun financialDialogHasScrollableBodyAndReachableActions() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get();activity.setTheme(R.style.Theme_TWBoardingScanner)
        val dialog=FinancialDialog(activity,FinancialDataProvider(activity),"6146","版面測試公司",fixture())
        dialog.show()
        val decor=dialog.window!!.decorView
        measure(decor,NeonUi.dp(activity,360),NeonUi.dp(activity,800))
        screenshot(decor,"financial-dialog-360")
        dialog.dismiss();controller.pause().stop().destroy()
    }
}
