package com.rex.twboardingscanner.domain

class ScoringEngine {
    private fun evaluate(s: StockSnapshot, type: RadarType, selected: Set<String>): SignalResult {
        val metrics = RuleMetrics(s)
        val checks = ScanConditions.forRadar(type).map { rule ->
            val state = when(rule.test(metrics)) { true -> CheckState.PASS; false -> CheckState.FAIL; null -> CheckState.PENDING }
            ConditionCheck(rule.id, rule.label, state, rule.id in selected, rule.extra)
        }
        val active = checks.filter { it.selected }
        val blockers = active.filter { it.state != CheckState.PASS }.map { "${it.label}：${if (it.state == CheckState.PENDING) "待查核" else "未通過"}" }.toMutableList()
        if (active.isEmpty()) blockers += "請至少勾選一項條件"
        val score = if (active.isEmpty()) 0 else active.count { it.state == CheckState.PASS } * 100 / active.size
        val upgraded = type == RadarType.B_DEEP_REVERSAL && blockers.isEmpty() && metrics.technicalUpgrade
        val reasons = active.filter { it.state == CheckState.PASS }.map { it.label }.toMutableList()
        if (upgraded) reasons += "技術升級：RSI>50 且收盤站上20日線"
        val light = if (blockers.isNotEmpty()) SignalLight.NONE else if (upgraded) SignalLight.RED else SignalLight.ORANGE
        return SignalResult(s.code, s.name, s.sector, type, score, light, reasons, blockers, s, checks, upgraded)
    }
    fun evaluateA(s: StockSnapshot, selected: Set<String> = defaults(RadarType.A_EARLY_BREAKOUT)) = evaluate(s, RadarType.A_EARLY_BREAKOUT, selected)
    fun evaluateB(s: StockSnapshot, selected: Set<String> = defaults(RadarType.B_DEEP_REVERSAL)) = evaluate(s, RadarType.B_DEEP_REVERSAL, selected)
    fun evaluateC(s: StockSnapshot, selected: Set<String> = defaults(RadarType.C_LONG_RED_VOLUME)) = evaluate(s, RadarType.C_LONG_RED_VOLUME, selected)
    private fun defaults(type: RadarType) = ScanConditions.forRadar(type).filter { it.defaultEnabled }.map { it.id }.toSet()
}
