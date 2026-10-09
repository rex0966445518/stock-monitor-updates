package com.rex.twboardingscanner.paper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.ui.PaperTradingActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperTradingTest {
    private fun at(time:String)=LocalDateTime.parse("2026-10-08T$time").atZone(TAIPEI).toInstant().toEpochMilli()
    private fun q(time:String="09:05:00",price:Double=100.0,code:String="1234")=PaperQuote(code,at(time),price,price-.5,price+.5,10.0,10.0,1000,110.0,90.0)
    private fun book()=PaperBook(enabled=true).apply{candidates.add(PaperCandidate("1234","測試公司","tse","A",100,at("08:59:00")))}
    @Test fun boundsAreTaipeiAndCutoffIsStrict(){
        assertFalse(PaperEngine.session(at("08:59:59")));assertTrue(PaperEngine.session(at("09:00:00")))
        assertTrue(PaperEngine.session(at("13:19:59")));assertFalse(PaperEngine.session(at("13:20:00")))
        assertFalse(PaperEngine.session(LocalDateTime.parse("2026-10-10T09:30:00").atZone(TAIPEI).toInstant().toEpochMilli()))
        val b=book();PaperEngine.step(b,mapOf("1234" to q("13:20:00")),at("13:20:00"));assertTrue(b.trades.isEmpty())
    }
    @Test fun staleFutureMissingAndSameSignalQuotesNeverFill(){
        listOf(q().copy(at=at("09:03:29")),q().copy(at=at("09:05:01")),q().copy(at=at("09:05:00")-86400000)).forEach{
            val b=book();PaperEngine.step(b,mapOf("1234" to it),at("09:05:00"));assertTrue(b.trades.isEmpty())
        }
        val b=book();b.candidates[0]=b.candidates[0].copy(observedAt=at("09:05:00"))
        PaperEngine.step(b,mapOf("1234" to q()),at("09:05:00"));assertTrue(b.trades.isEmpty())
        PaperEngine.step(b,emptyMap(),at("09:06:00"));assertTrue(b.trades.isEmpty())
    }
    @Test fun lotAccountingCostsAndRetriesAreExact(){
        val b=book();PaperEngine.step(b,mapOf("1234" to q()),at("09:05:00"))
        assertEquals(1,b.trades.size);assertEquals(1000,b.trades[0].shares)
        assertEquals(101.0,b.trades[0].price,.001);assertEquals(144.0,b.trades[0].fee,.001)
        assertEquals(3000000-101144.0,b.cash,.001)
        PaperEngine.step(b,mapOf("1234" to q()),at("09:05:00"));assertEquals(1,b.trades.size)
        PaperEngine.step(b,mapOf("1234" to q("09:10:00",97.0)),at("09:10:00"))
        assertEquals(2,b.trades.size);assertEquals("SELL",b.trades[1].side);assertTrue(b.positions.isEmpty())
        val sell=b.trades[1];assertEquals(sell.price*1000-sell.fee-sell.tax-101144,sell.realized,.001)
        assertEquals(b.capital+sell.realized,b.cash,.001)
        assertEquals(PaperEngine.equity(b)-b.capital,PaperEngine.realized(b)+PaperEngine.unrealized(b),.001)
        PaperEngine.step(b,mapOf("1234" to q("09:11:00",100.0)),at("09:11:00"));assertEquals(2,b.trades.size)
    }
    @Test fun liquidityCashLimitsAndPausedBooksNeverBuy(){
        val poor=book().apply{cash=50000.0};PaperEngine.step(poor,mapOf("1234" to q()),at("09:05:00"));assertTrue(poor.trades.isEmpty())
        listOf(q().copy(askLots=0.0),q().copy(ask=110.0),q().copy(volume=499)).forEach{val b=book();PaperEngine.step(b,mapOf("1234" to it),at("09:05:00"));assertTrue(b.trades.isEmpty())}
        val paused=book().apply{enabled=false};PaperEngine.step(paused,mapOf("1234" to q()),at("09:05:00"));assertTrue(paused.trades.isEmpty())
        val old=book();old.candidates[0]=old.candidates[0].copy(observedAt=at("09:05:00")-8*86400000L);PaperEngine.step(old,mapOf("1234" to q()),at("09:05:00"));assertTrue(old.trades.isEmpty())
    }
    @Test fun maxFiveDistinctStocksAndNoCrossRadarDuplicate(){
        val b=book();b.candidates.clear();(0..6).forEach{i->b.candidates.add(PaperCandidate("${1234+i}","測試","tse","A",100,at("08:59:00")))}
        b.candidates.add(b.candidates.first().copy(radar="C"))
        PaperEngine.step(b,(0..6).associate{i->"${1234+i}" to q(code="${1234+i}")},at("09:05:00"))
        assertEquals(5,b.positions.size);assertEquals(5,b.trades.map{it.code}.distinct().size)
    }
    @Test fun takeProfitTrailingAndAfternoonExitUseSubsequentBids(){
        val b=book();PaperEngine.step(b,mapOf("1234" to q()),at("09:05:00"))
        PaperEngine.step(b,mapOf("1234" to q("09:15:00",108.0)),at("09:15:00"));assertTrue(b.trades.last().reason.contains("停利"))
        val trail=book();PaperEngine.step(trail,mapOf("1234" to q()),at("09:05:00"))
        PaperEngine.step(trail,mapOf("1234" to q("09:15:00",105.0)),at("09:15:00"));assertEquals(1,trail.trades.size)
        PaperEngine.step(trail,mapOf("1234" to q("09:16:00",102.0)),at("09:16:00"));assertTrue(trail.trades.last().reason.contains("回落"))
        val close=book();PaperEngine.step(close,mapOf("1234" to q()),at("09:05:00"))
        PaperEngine.step(close,mapOf("1234" to q("13:15:00",101.0)),at("13:15:00"));assertEquals("13:15 收束平倉",close.trades.last().reason)
        val late=book();PaperEngine.step(late,mapOf("1234" to q()),at("09:05:00"))
        PaperEngine.step(late,mapOf("1234" to q("13:15:00").copy(bidLots=0.0)),at("13:15:00"));assertEquals(1,late.positions.size)
        PaperEngine.step(late,mapOf("1234" to q("13:20:00")),at("13:20:00"));assertEquals(1,late.trades.size);assertEquals(1,late.positions.size)
    }
    @Test fun capturedOfficialQuoteParsesAndTrialOrWrongExchangeRejects(){
        val raw=javaClass.getResource("/closing-5243.json")!!.readText()
        val parsed=PaperQuoteProvider.parse(raw,listOf("5243" to "tse"));assertEquals(105.0,parsed.getValue("5243").last,.001)
        assertEquals(35.0,parsed.getValue("5243").bidLots,.001);assertEquals(19.0,parsed.getValue("5243").askLots,.001)
        assertFalse(PaperEngine.fresh(parsed.getValue("5243"),at("13:30:00")))
        assertTrue(PaperQuoteProvider.parse(raw,listOf("5243" to "otc")).isEmpty())
        assertTrue(PaperQuoteProvider.parse(raw.replace("\"ip\": \"0\"","\"ip\": \"1\""),listOf("5243" to "tse")).isEmpty())
    }
    @Test fun persistenceRoundTripKeepsTradesAndRejectsCorruption(){
        val repo=PaperRepository(RuntimeEnvironment.getApplication());val b=book();PaperEngine.step(b,mapOf("1234" to q()),at("09:05:00"))
        val restored=repo.decode(repo.encode(b));assertEquals(b,restored)
        PaperEngine.step(restored,mapOf("1234" to q()),at("09:05:00"));assertEquals(1,restored.trades.size)
        assertThrows(Exception::class.java){repo.decode("{bad}")}
    }
    @Test fun simulatorScreenRendersOnSmallPhone(){
        val app=RuntimeEnvironment.getApplication<android.app.Application>();val repo=PaperRepository(app)
        val b=book();PaperEngine.step(b,mapOf("1234" to q()),at("09:05:00"));b.enabled=false
        app.getSharedPreferences("paper_trading_v1",0).edit().putString("book",repo.encode(b)).commit()
        val controller=Robolectric.buildActivity(PaperTradingActivity::class.java).setup();val view=controller.get().window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));view.layout(0,0,720,1600)
        fun check(v:View){if(v is TextView && v.layout!=null && v.text.isNotEmpty())assertTrue("clipped ${v.text}",v.layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+2);if(v is ViewGroup)for(i in 0 until v.childCount)check(v.getChildAt(i))}
        check(view)
        val bitmap=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));val file=File("build/ui-previews/paper-platform-360.png");file.parentFile.mkdirs();file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        controller.pause().stop().destroy();app.getSharedPreferences("paper_trading_v1",0).edit().clear().commit()
    }
}
