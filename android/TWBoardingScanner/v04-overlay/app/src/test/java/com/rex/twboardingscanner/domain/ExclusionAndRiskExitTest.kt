package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.paper.PaperStrategyConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ExclusionAndRiskExitTest{
    private val day=LocalDate.of(2026,10,8)
    private fun bars()=(List(80){100.0}+listOf(104.0,80.0,80.5)).mapIndexed{i,p->
        DailyBar(day.minusDays((81-i).toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p,p+1,p-1,p,1000000)}
    private fun snapshot()=TechnicalCalculator().build(MarketStock("2330","測試",Market.TWSE,StockSector.SEMICONDUCTOR,80.0,81.0,79.0,80.0,0.0,1000,null,null),bars().take(82))
    private fun evaluate(type:RadarType,s:StockSnapshot,excluded:Set<String>)=when(type){
        RadarType.A_EARLY_BREAKOUT->ScoringEngine().evaluateA(s,setOf("price"),excluded)
        RadarType.B_DEEP_REVERSAL->ScoringEngine().evaluateB(s,setOf("price"),excluded)
        RadarType.C_LONG_RED_VOLUME->ScoringEngine().evaluateC(s,setOf("price"),excluded)
        RadarType.D_MAIN_FORCE->ScoringEngine().evaluateD(s,setOf("price"),excluded)
        RadarType.E_DIF_TOUCH->ScoringEngine().evaluateE(s,setOf("price"),excluded)
    }
    @Test fun bothFreshBearishEventsMustOccurOnTheSameDay(){
        assertTrue(BearishTurn.Evidence(.01,-.01,70.0,69.9).passes)
        assertFalse(BearishTurn.Evidence(.01,0.0,70.0,69.9).passes)
        assertFalse(BearishTurn.Evidence(-.02,-.03,80.0,60.0).passes)
        assertFalse(BearishTurn.Evidence(.01,-.01,69.0,60.0).passes)
        assertFalse(BearishTurn.Evidence(.01,-.01,80.0,70.0).passes)
        assertTrue(BearishTurn.inspect(snapshot().bars.map{it.close})!!.passes)
        assertFalse(BearishTurn.inspect(bars().map{it.close})!!.passes)
        assertNull(BearishTurn.inspect(List(30){100.0}))
    }
    @Test fun uncheckedDoesNotBlockButCheckedVetoRemovesExistingHitInEveryRadarAndPaper(){
        val s=snapshot()
        for(type in RadarType.entries){
            val allowed=evaluate(type,s,emptySet());val veto=evaluate(type,s,setOf(BearishTurn.ID))
            assertNotEquals(SignalLight.NONE,allowed.light)
            assertEquals(SignalLight.NONE,veto.light)
            assertTrue(veto.blockers.any{it.contains("排除區")})
            assertEquals(CheckState.FAIL,veto.exclusionChecks.single().state)
            val rules=RadarType.entries.associateWith{if(it==type)setOf("price")else emptySet()}
            val settings=BtRobotSpace.settings(rules).copy(enabledRadars=setOf(type),stockScope=StockScope.SCANNER,marketGuardVersion=MarketCrashGuard.VERSION)
            assertTrue(PaperStrategyConfig.matches(allowed,settings))
            assertFalse(PaperStrategyConfig.matches(allowed,settings.copy(exclusions=settings.exclusions+mapOf(type to setOf(BearishTurn.ID)))))
        }
        val short=s.copy(bars=s.bars.take(20))
        assertEquals(SignalLight.NONE,evaluate(RadarType.A_EARLY_BREAKOUT,short,setOf(BearishTurn.ID)).light)
    }
    @Test fun exclusionCacheAndJournalBackupPreserveBothVetoAndIndependentRiskExit(){
        val series=listOf(BtSeries("2330","測試",Market.TWSE,bars()))
        val rules=RadarType.entries.associateWith{setOf("price")}
        val settings=BtRobotSpace.settings(rules).copy(start=day,end=day.plusDays(1),enabledRadars=RadarType.entries.toSet(),stockScope=StockScope.ROBOT,
            exclusions=RadarType.entries.associateWith{setOf(BearishTurn.ID)},exitRules=listOf(ExitRule(bearishExit=true)),marketGuardVersion=MarketCrashGuard.VERSION)
        val ref=BacktestEngine.prepare(series,settings)
        assertEquals("A/B/E",ref.signals.getValue(day).single().radar)
        assertEquals("C/D",ref.signals.getValue(day.plusDays(1)).single().radar)
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"veto-facts.gz")
        val fp=BtConditionIndex.fingerprint("fixture",settings)
        BtConditionIndex.build(series,settings).save(file,fp)
        assertEquals(ref.signals,BtConditionIndex.read(file,series,fp).prepare(settings).signals)
        assertEquals(settings,BtSettingsCodec.decode(BtSettingsCodec.encode(settings)))
        val old=BtSettingsCodec.encode(settings);old.remove("exclusions")
        assertEquals(ExclusionConditions.empty(),BtSettingsCodec.decode(old).exclusions)
        val app=RuntimeEnvironment.getApplication();val store=BtRobotStore(app)
        val id=BtRobotJournalSync.createBatch(store,settings,"risk-journal")
        val zip=File(app.cacheDir,"veto-risk.zip");BtRobotCheckpoint.export(app,id,zip)
        val imported=zip.inputStream().use{BtRobotCheckpoint.restore(app,it)}
        assertEquals(settings,BtSettingsCodec.decode(store.session(imported)!!.getJSONObject("settings")))
    }
    @Test fun technicalRiskExitHandlesProfitAndLossWithoutChangingLegacyLossRows(){
        val input=bars()
        val rule=listOf(ExitRule(bearishExit=true))
        assertEquals(0,ExitRules.match(rule,day.minusDays(3),day,5.0,input))
        assertEquals(0,ExitRules.match(rule,day.minusDays(3),day,-5.0,input))
        assertNull(ExitRules.match(rule,day.minusDays(3),day.plusDays(1),5.0,input))
        assertNull(ExitRules.match(listOf(ExitRule(days=0)),day.minusDays(3),day,5.0,input))
        assertEquals(rule,ExitRules.read(ExitRules.json(rule)))
        assertEquals(List(5){ExitRule()}+rule,ExitRules.read(ExitRules.json(List(5){ExitRule()}+rule)))
        assertTrue(runCatching{ExitRules.validate(List(6){ExitRule()})}.isFailure)
    }
}
