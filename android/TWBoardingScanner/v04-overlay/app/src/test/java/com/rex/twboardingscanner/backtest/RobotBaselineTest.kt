package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BtRobotActivity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.time.LocalDate
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RobotBaselineTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val c=RadarType.C_LONG_RED_VOLUME
    private val day=LocalDate.of(2026,8,1)
    private fun base()=BtSettings(day,day.plusDays(3),rules=BtRobotSpace.rules("0"),enabledRadars=setOf(c),stockScope=StockScope.ROBOT)
    private fun stocks()=listOf(BtSeries("2330","fixture",Market.TWSE,List(80){i->
        val close=100.0+i*.1
        DailyBar(day.minusDays(76).plusDays(i.toLong()).atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close-.1,close+1,close-1,close,1000000L)
    }))
    private fun find(v:View,tag:String):TextView? {
        if(v.tag==tag)return v as TextView
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it}
        return null
    }
    @Test fun baselineIsFirstEvenWithMustHavesThenEveryFreeOptionExactlyOnce() {
        val must=BtRobotSpace.key(BtRobotSpace.rules("0")+mapOf(c to setOf("price")))
        val blocked=BtRobotSpace.key(BtRobotSpace.rules("0")+mapOf(c to setOf("volume")))
        val before=BtRobotSingleOption.keys(must,blocked,setOf(c))
        val after=BtRobotSingleOption.keys(must,blocked,setOf(c),true)
        assertEquals(listOf("0")+before,after)
        assertEquals(after.size,after.distinct().size)
        var cursor=BigInteger.ZERO;val seen=mutableSetOf<String>()
        after.forEach { key->
            val next=BtRobotSingleOption.next(must,blocked,setOf(c),cursor,true){it in seen}!!
            assertEquals(key,next.key);seen+=key;cursor=next.cursor
            if(key!="0")assertEquals(1,BigInteger(key,16).andNot(BigInteger(must,16)).bitCount())
        }
        assertNull(BtRobotSingleOption.next(must,blocked,setOf(c),cursor,true){it in seen})
        assertEquals(BigInteger.valueOf(36),BtRobotSingleOption.total("0","0",setOf(RadarType.F_VOLUME_CONTRACTION),true))
        assertEquals(listOf("0"),BtRobotSingleOption.keys("0",BtRobotSpace.key(BtRobotSpace.rules("0")+mapOf(c to ScanConditions.forRadar(c).map{it.id}.toSet())),setOf(c),true))
    }
    @Test fun emptyBaselineActuallyTradesAndPreservesControlsWithoutEnablingOtherRadars() {
        val base=base().copy(exclusions=ExclusionConditions.empty()+mapOf(c to ExclusionConditions.forRadar(c).map{it.id}.toSet()))
        val baseline=BtRobotSingleOption.settings(base,"0",true)
        assertTrue(baseline.rules.values.all{it.isEmpty()});assertTrue(baseline.exclusions.values.all{it.isEmpty()})
        assertEquals(base.capital,baseline.capital,0.0);assertEquals(base.maxHoldingStocks,baseline.maxHoldingStocks);assertEquals(base.exitRules,baseline.exitRules)
        assertEquals(baseline,BtSettingsCodec.decode(BtSettingsCodec.encode(baseline)))
        val stocks=stocks();val index=BtConditionIndex.build(stocks,base)
        val prepared=index.prepare(baseline)
        assertEquals(BacktestEngine.prepare(stocks,baseline),prepared)
        assertTrue(prepared.signals.isNotEmpty());assertTrue(prepared.signals.values.flatten().all{it.radar=="C"})
        assertTrue(index.prepare(base.copy(exclusions=ExclusionConditions.empty())).signals.isEmpty()) // ordinary blank settings remain disabled
        assertTrue(BacktestEngine.run(prepared,baseline).buys>0)
        val denied=prepared.copy(marketGuards=prepared.days.associateWith{MarketCrashGuard.unknown("fixture")})
        assertEquals(0,BacktestEngine.run(denied,baseline.copy(marketGuardVersion=1)).buys)
    }
    @Test fun baselineReportCardCheckpointAndResumeKeepFirstRoundOnlyOnce() {
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
        val store=BtRobotStore(app);val base=base()
        val id=store.createSingle(base);val token=store.resume(id)
        val count=store.remaining(id)
        val next=store.next(id)!!;assertEquals("0",next.key)
        val settings=BtRobotSingleOption.settings(base,next.key,true)
        val run=BacktestEngine.run(BacktestEngine.prepare(stocks(),settings),settings)
        store.commit(id,token,next,BtResult(settings,run,1,1,emptyList(),"baseline fixture"),1)
        assertEquals(count-BigInteger.ONE,store.remaining(id))
        assertTrue(store.topTrials(id,1).single().getBoolean("baseline"))
        assertTrue(store.report(id,"0")!!.getBoolean("baseline"))
        val activity=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        assertTrue(find(activity.get().window.decorView,"robot-baseline")!!.text.contains("未勾選任何選股條件"))
        activity.pause().stop().destroy()
        BtRobotDataset.write(store.dataset(id),BacktestData.Loaded(stocks(),1,emptyList()))
        store.coverage(id,token,1,1,BtRobotCheckpoint.digest(store.dataset(id)));store.pause(id)
        val zip=File(app.cacheDir,"baseline-checkpoint.zip")
        BtRobotCheckpoint.export(app,id,zip)
        val restored=zip.inputStream().use{BtRobotCheckpoint.restore(app,it)}
        assertTrue(BtRobotSingleOption.baselineFirst(store.session(restored)))
        assertNotEquals("0",store.next(restored)!!.key)
        assertEquals(count-BigInteger.ONE,store.remaining(restored))
        assertTrue(store.report(restored,"0")!!.getBoolean("baseline"))
        zip.delete()
    }
}
