package com.rex.twboardingscanner.paper

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ScanProfitTest {
    private fun at(hour:Int,minute:Int)=LocalDateTime.of(2026,10,14,hour,minute).atZone(TAIPEI).toInstant().toEpochMilli()
    private fun quote(at:Long,price:Double)=PaperQuote("2330",at,price,price,price,100.0,100.0,10000,200.0,50.0)
    @Test fun entryAndExitRetainTheirOriginalScanAcrossRepublishAndBookRestart(){
        val app=RuntimeEnvironment.getApplication()
        val repo=PaperRepository(app)
        val book=PaperBook(enabled=true)
        book.candidates.add(PaperCandidate("2330","台積電","tse","A",100,at(9,0),"first"))
        PaperEngine.step(book,mapOf("2330" to quote(at(9,1),100.0)),at(9,1))
        assertEquals("first",book.positions.single().scanId)
        val reopened=repo.decode(repo.encode(book))
        reopened.candidates.clear()
        reopened.candidates.add(PaperCandidate("2330","台積電","tse","C",100,at(9,2),"second"))
        PaperEngine.step(reopened,mapOf("2330" to quote(at(9,3),110.0)),at(9,3))
        assertTrue(reopened.trades.any{it.side=="SELL"})
        assertTrue(reopened.trades.all{it.scanId=="first"})
        assertEquals(PaperEngine.realized(reopened),PaperEngine.scanProfit(reopened,"first").total,0.0)
        assertEquals(0,PaperEngine.scanProfit(reopened,"second").buys)
    }
    @Test fun batchesNeverShareProfitEvenForTheSameCodeAndLegacyTradesStayUnassigned(){
        val book=PaperBook()
        book.trades.addAll(listOf(
            PaperTrade("2330","台積電","A","BUY",1,1,100.0,143.0,0.0,0.0,"",scanId="one"),
            PaperTrade("2330","台積電","A","SELL",2,2,110.0,157.0,330.0,9000.0,"",scanId="one"),
            PaperTrade("2330","台積電","C","SELL",3,3,90.0,129.0,270.0,-10000.0,"",scanId="two"),
            PaperTrade("2330","台積電","A","SELL",4,4,120.0,171.0,360.0,20000.0,"")))
        book.positions.add(PaperPosition("2454","聯發科","tse","A",100.0,100143.0,1,101.0,101.0,5,scanId="one"))
        val p=PaperEngine.scanProfit(book,"one")
        assertEquals(9000.0,p.realized,0.0)
        assertEquals(PaperEngine.netSell(101.0)-100143.0,p.unrealized,0.0)
        assertEquals(0.0,PaperEngine.scanProfit(book,"unused").total,0.0)
        assertEquals(-10000.0,PaperEngine.scanProfit(book,"two").total,0.0)
        val repo=PaperRepository(RuntimeEnvironment.getApplication())
        val restored=repo.decode(repo.encode(book))
        assertEquals(p,PaperEngine.scanProfit(restored,"one"))
        val old=org.json.JSONObject(repo.encode(book))
        for(key in listOf("candidates","positions","trades")){
            val rows=old.getJSONArray(key)
            for(i in 0 until rows.length())rows.getJSONObject(i).remove("scanId")
        }
        assertTrue(repo.decode(old.toString()).trades.all{it.scanId.isEmpty()})
    }
}
