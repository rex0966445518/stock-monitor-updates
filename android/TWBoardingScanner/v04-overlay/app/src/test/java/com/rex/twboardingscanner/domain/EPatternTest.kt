package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.paper.PaperStrategyConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigInteger
import java.time.LocalDate
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class EPatternTest{
    private val e=RadarType.E_DIF_TOUCH
    private fun fact(dif:Double,signal:Double,oldDif:Double,oldSignal:Double,cfg:EPatternConfig=EPatternConfig.DEFAULT)=
        EPattern.Evidence(dif,signal,oldDif,oldSignal,100.0,cfg)
    @Test fun approachingAndJustCrossingWorkAboveAndBelowZeroButFallingAndOldCrossesFail(){
        assertTrue(fact(.9,1.0,.7,1.0).passes)
        assertTrue(fact(-1.4,-1.3,-1.7,-1.3).passes)
        assertTrue(fact(1.1,1.0,.8,1.0).passes)
        assertTrue(fact(1.0,1.0,.8,1.0).passes)
        assertFalse(fact(.8,1.0,.9,1.0).passes)
        assertFalse(fact(1.2,1.0,1.1,1.0).passes)
        assertFalse(fact(.6,1.0,.5,1.0).passes)
        assertFalse(fact(.8,1.0,.7,.8).passes) // DIF rises but distance worsens
        assertFalse(fact(1.1,1.0,.8,1.0,EPatternConfig(includeCross=false)).passes)
        assertFalse(fact(.9,1.0,.7,1.0,EPatternConfig(tolerancePct=.05)).passes)
        assertTrue(fact(.9,1.0,.7,1.0,EPatternConfig(tolerancePct=.1)).passes)
    }
    @Test fun missingInvalidAndUnwarmedDailyPricesStayPending(){
        assertNull(EPattern.inspect(List(30){100.0}))
        assertNull(EPattern.inspect(List(31){if(it==30)Double.NaN else 100.0}))
        assertNull(EPattern.inspect(List(31){if(it==30)0.0 else 100.0}))
        assertFalse(EPattern.inspect(List(31){100.0})!!.passes)
        for(x in listOf(0.0,Double.NaN,2.01,-.1))assertTrue(runCatching{EPatternConfig(x).validated()}.isFailure)
        assertEquals(setOf(EPattern.EVENT),ScanConditions.forRadar(e).filter{it.defaultEnabled}.map{it.id}.toSet())
    }
    private val start=LocalDate.of(2026,1,1)
    private fun bars()=(0..179).map{i->
        val p=100.0+5.0*sin(i/8.0)
        DailyBar(start.plusDays(i.toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p,p+1,p-1,p,1000000)
    }
    private fun settings()=BtRobotSpace.settings(RadarType.entries.associateWith{if(it==e)setOf(EPattern.EVENT)else emptySet()}).copy(
        start=start.plusDays(60),end=start.plusDays(179),enabledRadars=setOf(e))
    @Test fun fiveVectorCacheMatchesReferenceAndNeverUsesTodaysOrFutureCandle(){
        val input=bars();val s=settings();val series=listOf(BtSeries("2330","測試",Market.TWSE,input))
        val reference=BacktestEngine.prepare(series,s)
        assertTrue(reference.signals.isNotEmpty())
        assertEquals(reference.signals,BtConditionIndex.build(series,s).prepare(s).signals)
        val dir=File(RuntimeEnvironment.getApplication().cacheDir,"e-cache-test").apply{mkdirs()}
        val file=File(dir,"facts.gz");val fp=BtConditionIndex.fingerprint("digest",s)
        BtConditionIndex.build(series,s).save(file,fp)
        assertEquals(reference.signals,BtConditionIndex.read(file,series,fp).prepare(s).signals)
        assertTrue(runCatching{BtConditionIndex.read(file,series,BtConditionIndex.fingerprint("digest",s.copy(eConfig=EPatternConfig(.1))))}.isFailure)
        val day=reference.signals.keys.minOrNull()!!
        assertTrue(reference.signals.getValue(day).all{it.radar=="E"&&it.dataDate!!<day&&it.dEvidence.contains("DIF")})
        val changed=series.map{it.copy(bars=input.map{b->if(RuleMetrics.tradingDate(b.time)>=day)b.copy(close=b.close*2)else b})}
        assertEquals(reference.signals[day],BacktestEngine.prepare(changed,s).signals[day])
        assertEquals(input.size-1,TechnicalCalculator().barsForRadar(input,e,start.plusDays(179)).size)
    }
    @Test fun existing106KeyPositionsAndOlderSettingsRemainExactWhileENewJournalSyncRoundTrips(){
        assertEquals(106,BtRobotSpace.v5Slots.size)
        assertEquals(BtRobotSpace.v5Slots,BtRobotSpace.allSlots.take(106))
        val prior=BigInteger.ONE.shiftLeft(106)-BigInteger.ONE
        assertEquals(prior.toString(16),BtRobotSpace.key(BtRobotSpace.rules(prior.toString(16))))
        assertTrue(BtRobotSpace.rules(prior.toString(16)).getValue(e).isEmpty())
        val s=settings().copy(eConfig=EPatternConfig(.08,false),marketGuardVersion=MarketCrashGuard.VERSION,stockScope=StockScope.ROBOT)
        assertEquals(s,BtSettingsCodec.decode(BtSettingsCodec.encode(s)))
        val old=BtSettingsCodec.encode(s).put("rulesVersion",ScanConditions.PRE_E_VERSION)
        old.getJSONObject("rules").remove(e.name);old.remove("eConfig")
        old.put("enabledRadars",org.json.JSONArray(listOf(RadarType.A_EARLY_BREAKOUT.name)))
        val restored=BtSettingsCodec.decode(old)
        assertTrue(restored.rules.getValue(e).isEmpty());assertFalse(e in restored.enabledRadars)
        val app=RuntimeEnvironment.getApplication()
        val store=BtRobotStore(app)
        val journal=JSONObject().put("state","DONE").put("settings",BtSettingsCodec.encode(s.copy(stockScope=StockScope.BACKTEST)))
        assertEquals(s,BtRobotJournalSync.fromJournal(journal))
        val id=BtRobotJournalSync.createBatch(store,s,"e-journal")
        val session=store.session(id)!!
        assertEquals(9,session.getInt("searchVersion"))
        assertEquals(s.rules,BtRobotSpace.rules(session.getString("required")))
        assertEquals(s.eConfig,BtSettingsCodec.decode(session.getJSONObject("settings")).eConfig)
        val zip=File(app.cacheDir,"e-checkpoint.zip")
        BtRobotCheckpoint.export(app,id,zip)
        val imported=BtRobotCheckpoint.restore(app,zip.inputStream())
        assertEquals(s.eConfig,BtSettingsCodec.decode(store.session(imported)!!.getJSONObject("settings")).eConfig)
    }
    @Test fun paperUsesItsOwnEThresholdInsteadOfTheScannerThreshold(){
        val prices=bars()
        val passing=(61..179).first{EPattern.inspect(prices.take(it).map{b->b.close})?.let{x->x.passes&&x.distancePct>.001}==true}
        val subset=prices.take(passing)
        val stock=MarketStock("2330","測試",Market.TWSE,StockSector.SEMICONDUCTOR,100.0,101.0,99.0,100.0,0.0,1000,null,null)
        val snapshot=TechnicalCalculator().build(stock,subset)
        val result=ScoringEngine().evaluateE(snapshot)
        assertNotEquals(SignalLight.NONE,result.light)
        val s=settings().copy(stockScope=StockScope.SCANNER,marketGuardVersion=MarketCrashGuard.VERSION)
        assertTrue(PaperStrategyConfig.matches(result,s))
        assertFalse(PaperStrategyConfig.matches(result,s.copy(eConfig=EPatternConfig(.001))))
        assertEquals(listOf(result),com.rex.twboardingscanner.ui.ResultFilters.select(listOf(result),7))
    }
}
