package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FPatternTest {
    private val start=LocalDate.of(2026,1,1)
    private val f=RadarType.F_VOLUME_CONTRACTION
    private fun bars(closes:List<Double>,volumes:List<Long>)=closes.mapIndexed{i,p->DailyBar(start.plusDays(i.toLong()).atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),p,p+1,p-1,p,volumes[i])}
    @Test fun strictPriceAndInclusiveEightyPercentBoundary(){
        assertEquals(true,FPattern.inspect(bars(listOf(100.0,101.0),listOf(1000000,800000))).passes)
        assertEquals(false,FPattern.inspect(bars(listOf(100.0,101.0),listOf(1000000,800001))).passes)
        assertEquals(false,FPattern.inspect(bars(listOf(100.0,100.0),listOf(1000000,800000))).passes)
        assertEquals(false,FPattern.inspect(bars(listOf(100.0,99.0),listOf(1000000,800000))).passes)
        assertNull(FPattern.inspect(bars(listOf(100.0,101.0),listOf(0,800000))).passes)
        assertNull(FPattern.inspect(emptyList()).passes)
    }
    @Test fun threeDaysRequireFourBarsAndEveryPair(){
        val b=bars(listOf(100.0,101.0,102.0,103.0),listOf(1000000,800000,640000,512000))
        val cfg=FPatternConfig(days=3)
        assertEquals(true,FPattern.inspect(b,cfg).passes)
        assertEquals(3,FPattern.inspect(b,cfg).streak)
        assertNull(FPattern.inspect(b.takeLast(3),cfg).passes)
        assertEquals(false,FPattern.inspect(b.mapIndexed{i,v->if(i==2)v.copy(close=100.0)else v},cfg).passes)
    }
    @Test fun averageExcludesTodayAndSharesConvertToLots(){
        val e=FPattern.inspect(bars(List(6){100.0+it},listOf(2000000,2000000,2000000,2000000,2000000,1000000)))
        assertEquals(50.0,e.averageRatio!!,1e-9)
        assertEquals(true,e.minimumPass)
        assertEquals(true,e.averagePass)
        assertEquals(setOf(FPattern.EVENT),ScanConditions.forRadar(f).filter{it.defaultEnabled}.map{it.id}.toSet())
    }
    @Test fun configAndOldSettingsRoundTrip(){
        val cfg=FPatternConfig(days=3,volumePct=70.0,minLots=1200)
        val settings=BtRobotSpace.settings().copy(fConfig=cfg,enabledRadars=setOf(f))
        assertEquals(settings,BtSettingsCodec.decode(BtSettingsCodec.encode(settings)))
        val old=BtSettingsCodec.encode(settings);old.remove("fConfig");old.getJSONObject("rules").remove(f.name);old.put("enabledRadars",org.json.JSONArray(listOf(RadarType.E_DIF_TOUCH.name)))
        val restored=BtSettingsCodec.decode(old)
        assertFalse(f in restored.enabledRadars)
        assertEquals(FPatternConfig.DEFAULT,restored.fConfig)
        assertEquals(BtRobotSpace.v5Slots+BtRobotSpace.eSlots+BtRobotSpace.rsiSlots,BtRobotSpace.v6Slots.dropLast(ScanConditions.forRadar(f).count{it.id !in ScanConditions.MOMENTUM_IDS}))
        assertTrue(runCatching{FPatternConfig(volumePct=100.0).validated()}.isFailure)
    }
    @Test fun allSixtyFourSwitchCombinationsMatchDirectAndCachedDetection(){
        val b=bars(List(90){100.0+it},List(90){if(it%2==0)2000000L else 1000000L})
        val series=listOf(BtSeries("2330","測試",Market.TWSE,b))
        val rules=RadarType.entries.associateWith{type->setOf(if(type==f)FPattern.EVENT else "price")}
        val base=BtRobotSpace.settings(rules).copy(start=start.plusDays(60),end=start.plusDays(89),enabledRadars=RadarType.entries.toSet())
        val cached=BtConditionIndex.build(series,base)
        for(mask in 0..63){
            val enabled=RadarType.entries.filterIndexed{i,_->mask and (1 shl i)!=0}.toSet()
            val s=base.copy(enabledRadars=enabled)
            val direct=BacktestEngine.prepare(series,s)
            assertEquals("switch mask $mask",direct.signals,cached.prepare(s).signals)
            assertTrue(direct.signals.values.flatten().all{signal->signal.radar.split('/').all{letter->enabled.any{it.name.startsWith(letter)}}})
            if(enabled.isEmpty())assertTrue(direct.signals.isEmpty()) else assertTrue(direct.signals.isNotEmpty())
        }
        val only=base.copy(enabledRadars=setOf(f))
        val signals=BacktestEngine.prepare(series,only).signals
        assertTrue(signals.values.flatten().all{it.dataDate!!<itDate(signals,it)})
        val day=signals.keys.minOrNull()!!
        val changed=series.map{it.copy(bars=b.map{bar->if(RuleMetrics.tradingDate(bar.time)>=day)bar.copy(close=1.0,volumeShares=999999999)else bar})}
        assertEquals(signals[day],BacktestEngine.prepare(changed,only).signals[day])
        assertNotEquals(BtConditionIndex.fingerprint("x",only),BtConditionIndex.fingerprint("x",only.copy(fConfig=FPatternConfig(days=2))))
    }
    private fun itDate(signals:Map<LocalDate,List<BtSignal>>,s:BtSignal)=signals.entries.first{it.value.contains(s)}.key
}
