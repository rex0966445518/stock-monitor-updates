package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BacktestProgressTest {
    private val c=RadarType.C_LONG_RED_VOLUME
    private fun settings()=BtSettings(LocalDate.of(2026,8,1),LocalDate.of(2026,10,8),enabledRadars=setOf(c))
    @Test fun onlyEnabledNonemptyConditionsAppearAndUseFrozenRunSettings() {
        val s=settings()
        assertEquals("C 突發爆量",BacktestProgress.activeLabels(s))
        assertEquals("未啟用條件",BacktestProgress.activeLabels(s.copy(rules=s.rules+mapOf(c to emptySet()))))
        val tracker=BacktestProgress(s)
        val text=tracker.format("依本次 C 突發爆量 條件掃描 0 / 100 檔",0)
        assertTrue(text.startsWith("本次執行：C 突發爆量"));assertFalse(text.contains("ABCDEF"))
        // Changing a separate draft cannot change the immutable snapshot used by this tracker.
        val draft=s.copy(enabledRadars=setOf(RadarType.A_EARLY_BREAKOUT))
        assertEquals("A 準備起漲",BacktestProgress.activeLabels(draft))
        assertTrue(tracker.format("歷史日線 0/100",0).contains("本次執行：C 突發爆量"))
    }
    @Test fun stageRateEstimatesRemainingAndResetsBetweenStagesAndRestarts() {
        val t=BacktestProgress(settings())
        assertTrue(t.format("歷史日線 0/100",1000).contains("估算中"))
        assertTrue(t.format("歷史日線 20/100 · 成功 20",21000).contains("1分20秒"))
        assertTrue(t.format("依本次 C 突發爆量 條件掃描 0 / 100 檔",22000).contains("估算中"))
        assertTrue(t.format("依本次 C 突發爆量 條件掃描 25 / 100 檔",27000).contains("15秒"))
        assertTrue(t.format("依本次 C 突發爆量 條件掃描 100 / 100 檔",30000).contains("本階段完成"))
        assertTrue(t.format("尾盤買入／淨利 3% 賣出 0/50",31000).contains("估算中"))
        assertTrue(t.format("尾盤買入／淨利 3% 賣出 10/50 · 2026-08-20",33000).contains("8秒"))
        assertTrue(t.format("尾盤買入／淨利 3% 賣出 1/50",34000).contains("估算中"))
        assertTrue(t.format("等待資料…",35000).contains("估算中"))
    }
    @Test fun engineProgressUsesOnlyCInsteadOfEveryCategory() {
        val s=settings()
        val bars=(0..69).map{i->DailyBar(s.start.minusDays(60).plusDays(i.toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),100.0,101.0,99.0,100.0,1_000_000)}
        val messages=mutableListOf<String>()
        BacktestEngine.prepare(listOf(BtSeries("2330","測試",Market.TWSE,bars)),s,progress={messages.add(it)})
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.all{it.contains("C 突發爆量")&&!it.contains("ABCDEF")&&!it.contains("A 準備起漲")})
    }
}
