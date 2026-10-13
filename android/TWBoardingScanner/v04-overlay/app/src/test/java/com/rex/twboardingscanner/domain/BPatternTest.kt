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
class BPatternTest{
    private fun bars(closes:List<Double>)=closes.mapIndexed{i,c->DailyBar((i+1)*86400000L,c-.2,c+1,c-1,c,1000000)}
    private fun falling()=bars(List(100){100.0-it*.4})
    private fun recovering()=(falling()+bars(listOf(60.0,61.0,62.0,63.0,64.0,65.0,68.0)).mapIndexed{i,b->b.copy(time=(101L+i)*86400000L)}).let{it.dropLast(1)+it.last().copy(volumeShares=2000000)}
    private val cfg=BPatternConfig(depthDays=100,eventDays=10)
    @Test fun deepFallingStockFailsStopAndMomentumDespiteItsDiscount(){
        val e=BPattern.inspect(falling(),cfg)
        assertEquals(true,e.depth);assertEquals(false,e.stop);assertEquals(false,e.momentum)
        assertEquals(false,e.event)
    }
    @Test fun reversalRequiresActualRecoveryAndPriorVolumeBaseline(){
        val e=BPattern.inspect(recovering(),cfg)
        assertEquals(true,e.depth);assertEquals(true,e.stop);assertEquals(true,e.momentum)
        assertEquals(true,e.event);assertEquals(true,e.breakout)
        val lowVolume=recovering().let{it.dropLast(1)+it.last().copy(volumeShares=1000000)}
        assertEquals(false,BPattern.inspect(lowVolume,cfg).breakout)
        val strict=cfg.copy(volumeMultiple=2.1)
        assertEquals(false,BPattern.inspect(recovering(),strict).breakout)
        val recentBreak=recovering().let{it.dropLast(1)+it.last().copy(low=58.0)}
        assertEquals(false,BPattern.inspect(recentBreak,cfg).stop)
    }
    @Test fun incompleteAndMalformedBarsNeverPass(){
        val short=BPattern.inspect(recovering().take(10),cfg)
        assertNull(short.depth);assertNull(short.stop);assertNull(short.momentum);assertNull(short.event)
        val bad=recovering().let{it.dropLast(1)+it.last().copy(close=Double.NaN)}
        assertNull(BPattern.inspect(bad,cfg).depth)
        assertNull(BPattern.inspect(bad,cfg).event)
        assertThrows(Exception::class.java){cfg.copy(stopDays=0).validated()}
        assertThrows(Exception::class.java){cfg.copy(volumeMultiple=Double.NaN).validated()}
    }
    @Test fun thresholdsSurviveJournalCodecAndChangingThemInvalidatesRobotCache(){
        val base=BtSettings(LocalDate.of(2025,8,1),LocalDate.of(2025,9,1),bConfig=cfg)
        val encoded=BtSettingsCodec.encode(base)
        assertEquals(cfg,BtSettingsCodec.decode(encoded).bConfig)
        val changed=base.copy(bConfig=cfg.copy(volumeMultiple=2.0))
        assertNotEquals(BtConditionIndex.fingerprint("fixture",base),BtConditionIndex.fingerprint("fixture",changed))
        assertTrue(BacktestSettingsDiff.compare(BtSettingsCodec.encode(changed),encoded).changes.any{it.text.startsWith("B 深跌反轉門檻")})
        val legacy=org.json.JSONObject(encoded.toString()).apply{remove("bConfig")}
        assertEquals(BPatternConfig.DEFAULT,BtSettingsCodec.decode(legacy).bConfig)
        assertEquals(base.rules,BtSettingsCodec.decode(legacy).rules)
    }
    @Test fun robotBitsAppendWithoutChangingAnyHistoricalSlot(){
        assertEquals(65,BtRobotSpace.legacySlots.size)
        assertEquals(BtRobotSpace.v7Slots,BtRobotSpace.allSlots.take(BtRobotSpace.v7Slots.size))
        assertEquals(BPattern.IDS.map{RadarType.B_DEEP_REVERSAL to it},BtRobotSpace.allSlots.takeLast(BPattern.IDS.size))
        val oldKey=java.math.BigInteger.ONE.shiftLeft(BtRobotSpace.v7Slots.size).subtract(java.math.BigInteger.ONE).toString(16)
        assertTrue(BtRobotSpace.rules(oldKey).getValue(RadarType.B_DEEP_REVERSAL).none{it in BPattern.IDS})
        assertEquals(java.math.BigInteger.ONE.shiftLeft(BtRobotSpace.v7Slots.size),BtRobotSpace.total("0",includeB=false))
        assertEquals(BPattern.EARLY,defaultBtRules().getValue(RadarType.B_DEEP_REVERSAL))
    }
}
