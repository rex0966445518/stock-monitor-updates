package com.rex.twboardingscanner.domain

class ScoringEngine {
    private fun evaluate(s: StockSnapshot, type: RadarType, selected: Set<String>): SignalResult {
        val rules = ScanConditions.forRadar(type)
        val passed = rules.associate { it.id to it.passes(s) }
        val blockers = rules.filter { it.id in selected && passed[it.id] != true }.map { it.label }.toMutableList()
        val minBars = if (type == RadarType.C_LONG_RED_VOLUME) 20 else 35
        if (s.bars.size < minBars) blockers += "歷史資料不足 ${minBars} 日"
        if (selected.isEmpty()) blockers += "尚未選擇掃描條件"
        // Score describes the full catalogue; unchecked rules never block eligibility.
        val score = (100.0 * rules.filter { passed[it.id] == true }.sumOf { it.weight } / rules.sumOf { it.weight }).toInt().coerceIn(0, 100)
        val light = when {
            blockers.isNotEmpty() -> SignalLight.NONE
            score >= 85 -> SignalLight.RED
            score >= 70 -> SignalLight.ORANGE
            else -> SignalLight.YELLOW
        }
        val reasons = rules.filter { it.id in selected && passed[it.id] == true }.map { it.label }
        return SignalResult(s.code, s.name, s.sector, type, score, light, reasons, blockers, s)
    }
    fun evaluateA(s: StockSnapshot, selected: Set<String> = defaults(RadarType.A_EARLY_BREAKOUT)) = evaluate(s, RadarType.A_EARLY_BREAKOUT, selected)
    fun evaluateB(s: StockSnapshot, selected: Set<String> = defaults(RadarType.B_DEEP_REVERSAL)) = evaluate(s, RadarType.B_DEEP_REVERSAL, selected)
    fun evaluateC(s: StockSnapshot, selected: Set<String> = defaults(RadarType.C_LONG_RED_VOLUME)) = evaluate(s, RadarType.C_LONG_RED_VOLUME, selected)
    private fun defaults(type: RadarType) = ScanConditions.forRadar(type).filter { it.defaultEnabled }.map { it.id }.toSet()
}
