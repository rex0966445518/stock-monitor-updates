package com.rex.twboardingscanner.ui

import org.junit.Assert.*
import org.junit.Test

class ChipKSevenPageNavigatorTest {
    @Test fun sevenPagesAreStableAndOrdered() {
        assertEquals(listOf("kline","chip_daily","institution","bull_bear","main_force","whales","financial"),
            ChipKPage.ordered.map { it.slug })
        assertEquals(7, ChipKPage.ordered.size)
    }

    @Test fun matchesVisibleTabsButRejectsTradingControlsAndLongRows() {
        assertTrue(ChipKPageNavigator.matches(ChipKPage.KLINE, "日Ｋ"))
        assertTrue(ChipKPageNavigator.matches(ChipKPage.WHALES, "大戶持股"))
        assertTrue(ChipKPageNavigator.matches(ChipKPage.FINANCIAL, "基本面"))
        assertFalse(ChipKPageNavigator.matches(ChipKPage.MAIN_FORCE, "主力買入下單"))
        assertFalse(ChipKPageNavigator.matches(ChipKPage.DAILY, "籌碼日報 — 更多教學與會員訂閱"))
        assertFalse(ChipKPageNavigator.matches(ChipKPage.INSTITUTION, "買入"))
    }

    @Test fun selectsOnlyConfirmedVisibleTabAndNeverMakesUpMissingPages() {
        val ready=listOf(ChipKTabNode(1,"法人",clickable=true,selected=true,top=50))
        assertEquals(ChipKPageDecision.Confirm("selected_tab"),
            ChipKPageNavigator.decide(ChipKPage.INSTITUTION,ready,false,false,false,0))
        val hidden=listOf(ChipKTabNode(1,"法人",clickable=true,top=1900,bottom=2000))
        assertEquals(ChipKPageDecision.Wait,
            ChipKPageNavigator.decide(ChipKPage.INSTITUTION,hidden,false,false,false,5))
    }

    @Test fun navigationTapAndEvidenceAreSeparate() {
        val visible=listOf(ChipKTabNode(9,"籌碼日報",clickable=true,top=120,bottom=170))
        assertEquals(ChipKPageDecision.Tap(9),
            ChipKPageNavigator.decide(ChipKPage.DAILY,visible,false,false,false,0))
        assertEquals(ChipKPageDecision.Wait,
            ChipKPageNavigator.decide(ChipKPage.DAILY,visible,true,false,true,0))
        assertEquals(ChipKPageDecision.Wait,
            ChipKPageNavigator.decide(ChipKPage.DAILY,visible,true,true,false,0))
        assertEquals(ChipKPageDecision.Confirm("changed_unverified"),
            ChipKPageNavigator.decide(ChipKPage.DAILY,visible,true,true,true,0))
    }

    @Test fun scrollIsBoundedAndNeverTouchesOrderControls() {
        val controls=listOf(
            ChipKTabNode(1,"交易下單",clickable=true,top=90),
            ChipKTabNode(2,"",scrollable=true,top=70,bottom=110),
            ChipKTabNode(3,"",scrollable=true,top=500,bottom=1800)
        )
        assertEquals(ChipKPageDecision.Scroll(2,true),
            ChipKPageNavigator.decide(ChipKPage.FINANCIAL,controls,false,false,false,0))
        assertEquals(ChipKPageDecision.Scroll(2,false),
            ChipKPageNavigator.decide(ChipKPage.FINANCIAL,controls,false,false,false,4))
        assertEquals(ChipKPageDecision.Wait,
            ChipKPageNavigator.decide(ChipKPage.FINANCIAL,controls,false,false,false,5))
    }
}
