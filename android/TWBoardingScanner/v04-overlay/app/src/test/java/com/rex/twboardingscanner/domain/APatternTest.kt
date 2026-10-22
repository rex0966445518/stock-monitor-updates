package com.rex.twboardingscanner.domain
import com.rex.twboardingscanner.backtest.BtRobotSpace
import org.junit.Assert.*
import org.junit.Test
class APatternTest {
    private fun bar(i:Int,o:Double=99.0,h:Double=100.0,l:Double=98.0,c:Double=99.0,v:Long=1000000)=DailyBar((i+1)*86400000L,o,h,l,c,v)
    private fun base()=List(30){i->bar(i,v=if(i>=25)400000 else 1000000)}
    private fun breakout()=base()+bar(30,99.5,102.0,99.0,101.5,2000000)
    private fun retest()=breakout()+bar(31,101.2,101.4,99.7,100.4,800000)
    @Test fun setupAndBreakoutUsePriorDataAndNeedBothVolumeAndStrongClose(){
        assertEquals(true,APattern.inspect(base()).setup)
        assertEquals(false,APattern.inspect(base()).breakout)
        assertEquals(true,APattern.inspect(breakout()).breakout)
        val weak=breakout().dropLast(1)+breakout().last().copy(volumeShares=500000)
        assertEquals(false,APattern.inspect(weak).breakout)
        val wick=breakout().dropLast(1)+breakout().last().copy(high=108.0)
        assertEquals(false,APattern.inspect(wick).breakout)
        val wide=base().map{it.copy(high=120.0)}
        assertEquals(false,APattern.inspect(wide).setup)
    }
    @Test fun retestIsHistoricalAndReentryMustFollowIt(){
        assertEquals(true,APattern.inspect(retest()).retest)
        assertEquals(false,APattern.inspect(retest()).reentry)
        val reentry=retest()+bar(32,100.5,103.0,100.3,102.5,1600000)
        assertEquals(true,APattern.inspect(reentry).reentry)
        assertEquals(false,APattern.inspect(breakout()).reentry)
        val failed=retest().dropLast(1)+retest().last().copy(low=97.0,close=98.5)
        assertEquals(false,APattern.inspect(failed).retest)
        assertEquals(false,APattern.inspect(failed+bar(32,c=102.5,h=103.0)).reentry)
        assertTrue(APattern.inspect(reentry).format().contains("固定平台 100.0"))
    }
    @Test fun highVolumeRetestAndExpiredEventsDoNotQualify(){
        val loud=retest().dropLast(1)+retest().last().copy(volumeShares=1800000)
        assertEquals(false,APattern.inspect(loud).retest)
        val expired=breakout()+List(11){i->bar(31+i,o=100.5,h=101.4,l=99.7,c=100.4,v=700000)}
        assertEquals(false,APattern.inspect(expired).retest)
    }
    @Test fun continuationRequiresTwoPostBreakoutDays(){
        val b=breakout()
        assertEquals(false,APattern.inspect(b).continuation)
        val good=b+bar(31,101.0,103.0,100.3,102.0,1500000)+bar(32,102.0,104.0,101.0,103.0,1500000)
        assertEquals(true,APattern.inspect(good).continuation)
        assertEquals(false,APattern.inspect(good.dropLast(1)+good.last().copy(volumeShares=100000)).continuation)
    }
    @Test fun missingAndMalformedDataRemainPending(){
        assertNull(APattern.inspect(base().take(20)).setup)
        assertNull(APattern.inspect(breakout().dropLast(1)+breakout().last().copy(volumeShares=0)).breakout)
        assertNull(APattern.inspect(listOf(bar(0,o=100.0,h=100.0,l=100.0,c=100.0))).quality)
        assertNull(APattern.inspect(breakout().dropLast(1)+breakout().last().copy(close=Double.NaN)).breakout)
    }
    @Test fun newOptionsStayOnlyInAAndDoNotMoveSavedRobotKeys(){
        assertEquals(65,BtRobotSpace.legacySlots.size)
        assertEquals(BtRobotSpace.v8Slots,BtRobotSpace.allSlots.take(BtRobotSpace.v8Slots.size))
        assertEquals(APattern.IDS.map{RadarType.A_EARLY_BREAKOUT to it},
            BtRobotSpace.allSlots.drop(BtRobotSpace.v8Slots.size).take(APattern.IDS.size))
        assertEquals(GPattern.rules().map{RadarType.G_MA5_WAVE to it.id},
            BtRobotSpace.previousSlots.takeLast(GPattern.rules().size))
        assertEquals(DPattern.CHIP_ORDER.map{RadarType.D_MAIN_FORCE to it},BtRobotSpace.allSlots.takeLast(5))
        assertEquals(BtRobotSpace.allSlots.size,BtRobotSpace.allSlots.distinct().size)
        RadarType.entries.forEach{type->
            val ids=ScanConditions.forRadar(type).map{it.id}
            assertEquals(ids.size,ids.distinct().size)
            assertTrue(ids.size<63)
            if(type!=RadarType.A_EARLY_BREAKOUT)assertTrue(ids.none{it in APattern.IDS})
        }
        for(preset in listOf(APattern.PREPARE,APattern.CONFIRMED,APattern.PULLBACK)){
            val rules=RadarType.entries.associateWith{if(it==RadarType.A_EARLY_BREAKOUT)preset else emptySet()}
            assertEquals(rules,BtRobotSpace.rules(BtRobotSpace.key(rules)))
            assertFalse("burst" in preset);assertFalse("box" in preset)
        }
    }
}
