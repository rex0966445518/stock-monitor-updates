package com.rex.twboardingscanner.paper

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.rex.twboardingscanner.backtest.BtSettings
import com.rex.twboardingscanner.backtest.defaultBtRules
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.PaperStrategyActivity
import com.rex.twboardingscanner.ui.PaperTradingActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class PaperStrategyParityTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun at(date:String,time:String)=LocalDateTime.parse("${date}T$time").atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli()
    private fun quote(date:String,time:String,price:Double=100.0,code:String="1234")=
        PaperQuote(code,at(date,time),price,price-.5,price+.5,10.0,10.0,1000,price*1.1,price*.9)
    private fun settings(start:String="2026-10-01",end:String="2026-10-31",max:Int=2,net:Double=2.0)=
        BtSettings(LocalDate.parse(start),LocalDate.parse(end),3000000.0,"",
            RadarType.entries.associateWith{if(it==RadarType.A_EARLY_BREAKOUT)setOf("price","volume")else emptySet()},
            StockSector.entries.toSet(),maxHoldingStocks=max,targetNetPct=net,
            stockScope=StockScope.SCANNER,marketGuardVersion=MarketCrashGuard.VERSION)
    private fun book(settings:BtSettings)=PaperBook(enabled=true,strategy=settings).apply {
        candidates+=PaperCandidate("1234","測試","tse","A",100,at("2026-10-08","08:59:00"))
    }
    private fun locate(root:View,tag:String):View?{
        if(root.tag==tag)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)locate(root.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Before fun reset(){app.getSharedPreferences("paper_trading_v1",0).edit().clear().commit()}
    @Test fun sameSettingsCanBeEditedAndPersistWithoutRewritingTrades(){
        val repo=PaperRepository(app)
        val initial=repo.read()
        assertNull(initial.strategy)
        val configured=settings(max=18,net=3.5).copy(capital=5000000.0,codes="2330,3661",
            sectors=setOf(StockSector.SEMICONDUCTOR),exitRules=ExitRules.example())
        val saved=repo.saveStrategy(configured)
        assertFalse(saved.enabled);assertEquals(5000000.0,saved.capital,0.0)
        assertEquals(5000000.0,saved.cash,0.0);assertTrue(saved.candidates.isEmpty())
        assertEquals(configured.copy(stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(app).read()),repo.read().strategy)
        assertEquals(saved,repo.decode(repo.encode(saved)))
        repo.setEnabled(true)
        val active=repo.read();assertTrue(active.enabled);assertEquals(18,active.strategy!!.maxHoldingStocks)
        val trade=PaperTrade("2330","台積電","A","BUY",1L,1L,100.0,142.0,0.0,0.0,"測試")
        val withTrade=active.apply{trades.add(trade);cash-=100142}
        app.getSharedPreferences("paper_trading_v1",0).edit().putString("book",repo.encode(withTrade)).commit()
        assertThrows(Exception::class.java){repo.saveStrategy(configured.copy(capital=4000000.0))}
        val changed=repo.saveStrategy(configured.copy(maxHoldingStocks=7,targetNetPct=5.0))
        assertEquals(1,changed.trades.size)
        assertEquals(trade,changed.trades.single())
        assertEquals(5000000.0,changed.capital,0.0)
        assertFalse(changed.enabled);assertEquals(7,changed.strategy!!.maxHoldingStocks)
    }
    @Test fun historicalNetExitAndMaximumHoldingsActuallyDriveLivePaperEngine(){
        val s=settings(max=2,net=3.0)
        val b=book(s)
        b.candidates.clear()
        (0..3).forEach{i->
            b.candidates+=PaperCandidate("${1234+i}","測試","tse","A",100,at("2026-10-08","08:59:00"))
        }
        val quotes=(0..3).associate{i->"${1234+i}" to quote("2026-10-08","09:05:00",code="${1234+i}")}
        PaperEngine.step(b,quotes,at("2026-10-08","09:05:00"))
        assertEquals(2,b.positions.size)
        assertEquals(2,b.trades.size)
        val only=PaperBook(enabled=true,strategy=s).apply {
            candidates+=PaperCandidate("1234","測試","tse","A",100,at("2026-10-08","08:59:00"))
        }
        PaperEngine.step(only,mapOf("1234" to quote("2026-10-08","09:05:00")),at("2026-10-08","09:05:00"))
        assertEquals(1,only.trades.size)
        PaperEngine.step(only,mapOf("1234" to quote("2026-10-08","13:15:00",101.0)),at("2026-10-08","13:15:00"))
        assertEquals(1,only.trades.size) // No old fixed 13:15 forced liquidation.
        PaperEngine.step(only,mapOf("1234" to quote("2026-10-09","09:05:00",107.0)),at("2026-10-09","09:05:00"))
        assertEquals(2,only.trades.size)
        assertTrue(only.trades.last().reason.contains("淨利至少 3%"))
        assertEquals("SELL",only.trades.last().side)
    }
    @Test fun dateWindowStopsNewBuysButStillPermitsManagingExistingHoldings(){
        val start=settings(start="2026-10-10",end="2026-10-30")
        val blocked=book(start)
        PaperEngine.step(blocked,mapOf("1234" to quote("2026-10-08","09:05:00")),at("2026-10-08","09:05:00"))
        assertTrue(blocked.trades.isEmpty());assertTrue(blocked.status.contains("日期"))
        // Once the entry was executed during a valid day, the end of the range
        // cannot strand existing holdings without sell management.
        val bought=book(settings(start="2026-10-01",end="2026-10-08",net=1.0))
        PaperEngine.step(bought,mapOf("1234" to quote("2026-10-08","09:05:00")),at("2026-10-08","09:05:00"))
        PaperEngine.step(bought,mapOf("1234" to quote("2026-10-09","09:05:00",107.0)),at("2026-10-09","09:05:00"))
        assertEquals("SELL",bought.trades.last().side)
        assertTrue(bought.positions.isEmpty())
    }
    @Test fun legacyBooksKeepOriginalThreePercentStopAnd13_15Flatten(){
        val legacy=PaperBook(enabled=true).apply{
            candidates+=PaperCandidate("1234","測試","tse","A",100,at("2026-10-08","08:59:00"))
        }
        PaperEngine.step(legacy,mapOf("1234" to quote("2026-10-08","09:05:00")),at("2026-10-08","09:05:00"))
        PaperEngine.step(legacy,mapOf("1234" to quote("2026-10-08","13:15:00",101.0)),at("2026-10-08","13:15:00"))
        assertTrue(legacy.trades.last().reason.contains("13:15"))
    }
    @Test fun paperRulesIgnoreScannerSelectedFlagsAndCheckIndependentSectorsAndMissingFacts(){
        val s=settings().copy(sectors=setOf(StockSector.SEMICONDUCTOR),codes="2330")
        val market=MarketStock("2330","台積電",Market.TWSE,StockSector.SEMICONDUCTOR,100.0,101.0,99.0,100.0,0.0,1000,null,null)
        val bars=(0..80).map{i->DailyBar(LocalDate.of(2026,10,8).minusDays((80-i).toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),100.0,101.0,99.0,100.0,1000000)}
        val snap=TechnicalCalculator().build(market,bars)
        val scanner=ScoringEngine().evaluateA(snap,setOf("extra_flow"))
        assertEquals(SignalLight.NONE,scanner.light) // Main scanner selected a missing check.
        assertTrue(PaperStrategyConfig.matches(scanner,s))
        val selected=PaperStrategyConfig.candidates(listOf(scanner),s,StockPolicy(),at("2026-10-08","13:10:00"))
        assertEquals(1,selected.size);assertEquals("2330",selected.single().code)
        val pending=s.copy(rules=s.rules+mapOf(RadarType.A_EARLY_BREAKOUT to setOf("extra_flow")))
        assertTrue(PaperStrategyConfig.candidates(listOf(scanner),pending,StockPolicy(),1L).isEmpty())
        assertTrue(PaperStrategyConfig.candidates(listOf(scanner),s,StockPolicy(banned=setOf("2330")),1L).isEmpty())
        assertTrue(PaperStrategyConfig.candidates(listOf(scanner),s.copy(sectors=setOf(StockSector.SHIPPING)),StockPolicy(),1L).isEmpty())
    }
    @Test fun paperConfigurationScreenExposesBacktestParametersAndPortfolioEntry(){
        val ctl=Robolectric.buildActivity(PaperStrategyActivity::class.java).setup()
        val view=ctl.get().window.decorView
        listOf("paper-start-date","paper-end-date","paper-capital","paper-max-holdings",
            "paper-net-target","paper-stock-codes","paper-sectors",
            "paper-rules-A_EARLY_BREAKOUT","paper-rules-B_DEEP_REVERSAL","paper-rules-C_LONG_RED_VOLUME",
            "paper-exit-rules","paper-policy-summary","paper-save-strategy","paper-load-backtest").forEach{
            assertNotNull("Missing setting: $it",locate(view,it))
        }
        (locate(view,"paper-max-holdings") as EditText).setText("16")
        (locate(view,"paper-net-target") as EditText).setText("2.5")
        assertEquals(16,ctl.get().capture().maxHoldingStocks)
        assertEquals(2.5,ctl.get().capture().targetNetPct,0.0)
        ctl.pause().stop().destroy()
        val paper=Robolectric.buildActivity(PaperTradingActivity::class.java).setup()
        assertNotNull(locate(paper.get().window.decorView,"paper-open-settings"))
        paper.pause().stop().destroy()
    }
}
