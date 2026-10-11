package com.rex.twboardingscanner.domain

import com.rex.twboardingscanner.backtest.*
import com.rex.twboardingscanner.data.TradeSideVolumeStore
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TrendTouchAndActiveFlowTest {
    private val date=LocalDate.of(2026,10,9)
    private fun snapshot(closes:List<Double>,flow:TradeSideVolume?=null,price:Double=closes.last()):StockSnapshot{
        val stock=MarketStock("2330","台積電",Market.TWSE,StockSector.SEMICONDUCTOR,
            price-1,price+1,price-2,price,2.0,1000,null,null,tradeSideVolume=flow)
        val bars=closes.mapIndexed{i,v->DailyBar(date.minusDays((closes.size-i-1).toLong())
            .atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
            v-1,v+2,v-2,v,1000000)}
        return TechnicalCalculator().build(stock,bars).copy(
            timestamp=date.atTime(13,30).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli())
    }
    @Test fun allThreeRadarsHaveNewOptionalRulesWhileHistoricalSlotsDoNotMove(){
        RadarType.entries.forEach{type->
            val options=ScanConditions.forRadar(type)
            assertEquals(1,options.count{it.id==ScanConditions.MA5_TOUCH_MA10})
            assertEquals(1,options.count{it.id==ScanConditions.ACTIVE_BUY_SELL_15})
            assertFalse(options.first{it.id==ScanConditions.MA5_TOUCH_MA10}.defaultEnabled)
            assertFalse(options.first{it.id==ScanConditions.ACTIVE_BUY_SELL_15}.defaultEnabled)
        }
        assertEquals(65,BtRobotSpace.legacySlots.size)
        assertEquals(71,BtRobotSpace.slots.size)
        assertEquals(BtRobotSpace.legacySlots,BtRobotSpace.slots.take(65))
        assertEquals(BigInteger.ONE.shiftLeft(65),BtRobotSpace.legacyTotal)
        assertEquals(BigInteger.ONE.shiftLeft(71),BigInteger.ONE.shiftLeft(BtRobotSpace.slots.size))
        val allLegacy=BtRobotSpace.legacyTotal-BigInteger.ONE
        assertEquals(allLegacy.toString(16),BtRobotSpace.key(BtRobotSpace.rules(allLegacy.toString(16))))
        val added=BtRobotSpace.key(RadarType.entries.associateWith{setOf(ScanConditions.MA5_TOUCH_MA10)})
        assertTrue(BigInteger(added,16)>=BtRobotSpace.legacyTotal)
    }
    @Test fun risingFiveMaMustApproachTenMaFromBelowWithFreshGapClosing(){
        // Prior five 98; prior ten average 100; latest 114 raises 5MA and
        // closes the 5MA/10MA distance from 2% to effectively 0%.
        val closes=List(18){100.0}+List(5){102.0}+List(5){98.0}+listOf(114.0)
        val m=RuleMetrics(snapshot(closes))
        assertEquals(true,m.ma5RisingTouchesMa10)
        assertEquals(true,m.ma5Up)
        assertEquals(false,RuleMetrics(snapshot(List(30){100.0})).ma5RisingTouchesMa10)
        assertNull(RuleMetrics(snapshot(List(10){100.0})).ma5RisingTouchesMa10)
    }
    @Test fun activeBuySellRatioUsesExecutedVolumeAndNeverDividesByZero(){
        val closes=List(30){100.0}
        val ok=TradeSideVolume(date,150000,100000,LocalTime.of(13,25),"licensed-broker")
        val almost=ok.copy(buyAtAsk=149999)
        val missing=snapshot(closes)
        assertNull(RuleMetrics(missing).activeBuySellAtLeast(1.5))
        assertEquals(true,RuleMetrics(snapshot(closes,ok)).activeBuySellAtLeast(1.5))
        assertEquals(false,RuleMetrics(snapshot(closes,almost)).activeBuySellAtLeast(1.5))
        assertNull(RuleMetrics(snapshot(closes,ok.copy(sellAtBid=0))).activeBuySellAtLeast(1.5))
        assertNull(RuleMetrics(snapshot(closes,ok.copy(date=date.minusDays(1)))).activeBuySellAtLeast(1.5))
        val beforeSnapshot=snapshot(closes,ok.copy(asOf=LocalTime.of(13,30)))
            .copy(timestamp=date.atTime(10,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli())
        assertNull(RuleMetrics(beforeSnapshot).activeBuySellAtLeast(1.5))
        val selected=setOf(ScanConditions.ACTIVE_BUY_SELL_15)
        val pending=ScoringEngine().evaluateA(missing,selected)
        assertEquals(CheckState.PENDING,pending.checks.single{it.id==ScanConditions.ACTIVE_BUY_SELL_15}.state)
        assertEquals(SignalLight.NONE,pending.light)
        val passes=ScoringEngine().evaluateA(snapshot(closes,ok),selected)
        assertEquals(CheckState.PASS,passes.checks.single{it.id==ScanConditions.ACTIVE_BUY_SELL_15}.state)
        assertTrue(passes.light!=SignalLight.NONE)
    }
    @Test fun historicalRuleVersionCanStillDecodePreviousSnapshotsWithoutRenumbering(){
        val rules=RadarType.entries.associateWith{type->setOf("price","volume")}
        val old=BtRobotSpace.settings(rules)
        val o=BtSettingsCodec.encode(old)
        o.put("rulesVersion",ScanConditions.LEGACY_VERSION)
        assertEquals(old,BtSettingsCodec.decode(o))
        val keys=rules.keys
        assertEquals(3,keys.size)
        assertEquals(BtRobotSpace.key(rules),BtRobotSpace.key(BtSettingsCodec.decode(o).rules))
    }
}
