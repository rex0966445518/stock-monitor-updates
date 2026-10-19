package com.rex.twboardingscanner.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ChipKGeminiPromptTest {
    @Test fun asksForEvidenceWithoutFabricatingData() {
        val prompt = ChipKGeminiPrompt.forStock("2327")
        assertTrue(prompt.contains("2327"))
        assertTrue(prompt.contains("無法辨識"))
        assertTrue(prompt.contains("未提供"))
        assertTrue(prompt.contains("券商分點"))
        assertTrue(prompt.contains("資料不足"))
        assertTrue(prompt.contains("絕非上漲機率"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidStockCode() {
        ChipKGeminiPrompt.forStock("123;DROP")
    }
}
