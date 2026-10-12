package com.rex.twboardingscanner.data

import org.junit.Assert.*
import org.junit.Test

class FinancialSourceRegressionTest {
    private fun source(name:String)=javaClass.getResource("/financial/$name-2889.html")!!.readText()
    @Test fun financialHoldingReportsKeepNegativeCashAndMargin() {
        val cash=HistockParser.periodValues(source("cash"),"2889","現金流量表","營業現金流").maxByOrNull{it.index}!!
        val margin=HistockParser.periodValues(source("margin"),"2889","利潤比率","營業利益率").maxByOrNull{it.index}!!
        assertEquals(2026,cash.year)
        assertEquals(2,cash.quarter)
        assertEquals(-17304776.0,cash.value,0.001)
        assertEquals(52.83,margin.value,0.001)
    }
}
