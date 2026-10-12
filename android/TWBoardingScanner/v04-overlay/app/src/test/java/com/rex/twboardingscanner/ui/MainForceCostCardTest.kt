package com.rex.twboardingscanner.ui

import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
class MainForceCostCardTest {
    private fun signal(hasTwentyDays:Boolean):SignalResult {
        val date=LocalDate.of(2026,9,1)
        val count=if(hasTwentyDays)30 else 18
        val bars=(0 until count).map{i->
            val costBase=if(i==count-1)95.0 else 100.0
            DailyBar(date.plusDays(i.toLong()).atTime(13,30)
                .atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),
                costBase,costBase+2,costBase-2,costBase,1000000L)
        }
        val stock=MarketStock("2467","志聖",Market.TWSE,StockSector.ELECTRONICS,
            95.0,97.0,93.0,95.0,-5.0,1000,null,null)
        val snap=TechnicalCalculator().build(stock,bars)
        return ScoringEngine().evaluateA(snap,setOf(ScanConditions.MAIN_FORCE_COST_20))
    }

    @Test fun scannerCardShowsEstimatedCostAndSourceNotProprietaryAccountCost(){
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        val adapter=SignalAdapter()
        adapter.submit(listOf(signal(true)))
        val holder=adapter.onCreateViewHolder(LinearLayout(activity.get()),0)
        adapter.onBindViewHolder(holder,0)
        assertTrue(holder.b.mainForceCost.text.toString().contains("估算主力成本（20日）"))
        assertTrue(holder.b.mainForceCost.text.toString().contains("現價低"))
        assertTrue(holder.b.mainForceCostNote.text.toString().contains("非實際大戶成本"))
        assertTrue(holder.b.mainForceCost.contentDescription.toString().contains("量價推算"))
        adapter.submit(listOf(signal(false)))
        adapter.onBindViewHolder(holder,0)
        assertTrue(holder.b.mainForceCost.text.toString().contains("資料不足"))
        assertTrue(holder.b.mainForceCostNote.text.toString().contains("20個有效交易日"))
        activity.pause().stop().destroy()
    }
}
