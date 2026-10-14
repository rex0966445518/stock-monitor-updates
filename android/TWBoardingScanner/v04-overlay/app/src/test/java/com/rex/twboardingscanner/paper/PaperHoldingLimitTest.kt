package com.rex.twboardingscanner.paper

import com.rex.twboardingscanner.backtest.BtSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PaperHoldingLimitTest {
    private val now=LocalDateTime.parse("2026-10-14T10:00:00").atZone(TAIPEI).toInstant().toEpochMilli()
    private fun populate(b:PaperBook):Map<String,PaperQuote> {
        b.candidateAt=now-1000
        return (1000..1024).associate{i->val code=i.toString()
            b.candidates.add(PaperCandidate(code,"測試$code","tse","A",100,now-1000))
            code to PaperQuote(code,now,100.0,99.5,100.5,10.0,10.0,1000,110.0,90.0)
        }
    }
    @Test fun limit24Buys24DistinctStocksAndNeverThe25th() {
        val b=PaperBook(enabled=true,holdingLimit=24)
        val quotes=populate(b)
        PaperEngine.step(b,quotes,now)
        assertEquals(24,b.positions.size);assertEquals(24,b.trades.size)
        PaperEngine.step(b,quotes,now)
        assertEquals(24,b.positions.size);assertEquals(24,b.trades.size)
        b.holdingLimit=5
        PaperEngine.step(b,quotes,now)
        assertEquals(24,b.positions.size);assertEquals(24,b.trades.size)
    }
    @Test fun savesAcrossReopenAndPreservesEnabledCandidatesCashAndHistory() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("paper_trading_v1",0).edit().clear().commit()
        val repo=PaperRepository(app)
        val b=PaperBook(enabled=true);populate(b)
        PaperEngine.step(b,b.candidates.associate{it.code to PaperQuote(it.code,now,100.0,99.5,100.5,10.0,10.0,1000,110.0,90.0)},now)
        app.getSharedPreferences("paper_trading_v1",0).edit().putString("book",repo.encode(b)).commit()
        val changed=repo.setHoldingLimit(24)
        assertTrue(changed.enabled);assertEquals(b.cash,changed.cash,0.0)
        assertEquals(b.trades,changed.trades);assertEquals(b.positions,changed.positions)
        assertEquals(b.candidates,changed.candidates)
        assertEquals(24,PaperRepository(app).read().maxHoldings)
        for(invalid in listOf(0,25,-1))assertTrue(runCatching{repo.setHoldingLimit(invalid)}.isFailure)
        assertEquals(24,repo.read().maxHoldings)
        val old=org.json.JSONObject(repo.encode(PaperBook())).apply{remove("holdingLimit")}
        assertEquals(5,repo.decode(old.toString()).maxHoldings)
    }
    @Test fun customStrategyUsesTheSameUpdatedLimit() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("paper_trading_v1",0).edit().clear().commit()
        val repo=PaperRepository(app)
        repo.saveStrategy(PaperStrategyConfig.defaults(app,3000000.0).copy(maxHoldingStocks=7))
        repo.setHoldingLimit(24)
        val b=PaperRepository(app).read()
        assertEquals(24,b.maxHoldings);assertEquals(24,b.strategy!!.maxHoldingStocks)
    }
}
