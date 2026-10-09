package com.rex.twboardingscanner.ui
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.rex.twboardingscanner.data.*
import com.rex.twboardingscanner.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClosingAuctionTest {
    private val now=ZonedDateTime.of(2026,10,9,16,15,0,0,RuleMetrics.TAIPEI)
    private fun raw()=javaClass.getResource("/closing-5243.json")!!.readText()
    private fun html()=javaClass.getResource("/closing-5243.html")!!.readText()
    private fun parse(s:String=raw())=ClosingAuctionProvider.parseQuote(s,"5243","tse",now)
    @Test fun capturedQuoteMatches268AndDownDespitePositiveDailyChange() {
        val a=parse();assertEquals(268L,a.lots);assertEquals("2026-10-08",a.date)
        val b=a.copy(beforePrice=ClosingAuctionProvider.previousPrice(html(),"5243.TW",a))
        assertEquals(106.0,b.beforePrice!!,0.001);assertEquals(AuctionDirection.DOWN,b.direction);assertEquals("−268 張",b.display)
        assertEquals("＋268 張",a.copy(beforePrice=104.0).display)
        assertEquals("268 張",a.copy(beforePrice=105.0).display)
        assertEquals(AuctionDirection.UNKNOWN,a.direction)
    }
    @Test fun rejectWrongStockDatesTimesAndMissingData() {
        val json=JSONObject(raw());val q=json.getJSONArray("msgArray").getJSONObject(0)
        q.getJSONObject("trade").put("t","14:30:00");assertNull(parse(json.toString()).lots)
        q.getJSONObject("trade").put("t","13:24:55");assertNull(parse(json.toString()).lots)
        q.getJSONObject("trade").put("t","13:33:00");assertEquals("13:33:00",parse(json.toString()).time)
        q.put("d","20260901");assertNull(parse(json.toString()).lots)
        assertNull(ClosingAuctionProvider.parseQuote(raw(),"2330","tse",now).lots)
        val a=parse();assertNull(ClosingAuctionProvider.previousPrice(html(),"2330.TW",a))
        assertNull(ClosingAuctionProvider.previousPrice(html().replace("2026-10-08","2026-10-07"),"5243.TW",a))
        assertNull(ClosingAuctionProvider.previousPrice(html(),"5243.TW",a.copy(price=104.0)))
        assertNull(ClosingAuctionProvider.parseQuote(raw(),"5243","tse",now.minusDays(1).withHour(12)).lots)
        val bad=JSONObject(raw());bad.getJSONArray("msgArray").getJSONObject(0).getJSONObject("trade").remove("v")
        assertNull(parse(bad.toString()).lots)
    }
    @Test fun enlargedRedGreenAndUnknownCardsRender() {
        val context=RuntimeEnvironment.getApplication()
        listOf(parse().copy(beforePrice=106.0),parse().copy(beforePrice=104.0),ClosingAuction(note="尚未收盤撮合")).forEachIndexed {i,a ->
            val v=ClosingAuctionView(context);v.bind(a)
            v.measure(View.MeasureSpec.makeMeasureSpec(672,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,v.measuredWidth,v.measuredHeight)
            val value=v.getChildAt(1) as TextView
            if(i==0)assertEquals(Color.rgb(47,209,138),value.currentTextColor)
            if(i==1)assertEquals(Color.rgb(255,73,108),value.currentTextColor)
            for(k in 0 until v.childCount){val t=v.getChildAt(k) as TextView;assertTrue(t.layout.height<=t.height+2)}
            val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
            val f=File("build/ui-previews/closing-$i.png");f.parentFile.mkdirs();f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        }
    }
    @Test fun historyPreservesAuctionAfterRestart() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase("signals.db")
        val auction=parse().copy(beforePrice=106.0)
        val stock=MarketStock("5243","測試",Market.TWSE,StockSector.ELECTRONICS,100.0,106.0,99.0,105.0,5.0,7940,null,null,closingAuction=auction)
        val bars=(0..39).map{DailyBar(now.minusDays((40-it).toLong()).toInstant().toEpochMilli(),100.0,106.0,99.0,105.0,7940000)}
        val result=ScoringEngine().evaluateA(TechnicalCalculator().build(stock,bars),setOf("price","volume"))
        SignalHistoryDb(context).use{assertTrue(it.insertIfNew(result))}
        SignalHistoryDb(context).use{val saved=it.queryByCode("5243").single().closingAuction!!;assertEquals(auction,saved)}
        context.deleteDatabase("signals.db")
    }
}
