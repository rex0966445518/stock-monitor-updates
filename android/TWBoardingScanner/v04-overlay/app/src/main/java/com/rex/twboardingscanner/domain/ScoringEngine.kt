package com.rex.twboardingscanner.domain

class ScoringEngine {
    private fun evaluate(s: StockSnapshot, type: RadarType, selected: Set<String>,excluded:Set<String> = emptySet()): SignalResult {
        val metrics = RuleMetrics(s)
        val checks = ScanConditions.forRadar(type).map { rule ->
            // New optional historical patterns must not slow existing unselected scans.
            val raw=if(rule.id in APattern.IDS&&rule.id !in selected)null else rule.test(metrics)
            val state = when(raw) { true -> CheckState.PASS; false -> CheckState.FAIL; null -> CheckState.PENDING }
            ConditionCheck(rule.id, rule.label, state, rule.id in selected, rule.extra)
        }
        val active = checks.filter { it.selected }
        val blockers = active.filter { it.state != CheckState.PASS }.map { "${it.label}：${if (it.state == CheckState.PENDING) "待查核" else "未通過"}" }.toMutableList()
        val vetoes=ExclusionConditions.forRadar(type).map{rule->
            val raw=rule.test(metrics)
            ConditionCheck(rule.id,rule.label,when(raw){true->CheckState.FAIL;false->CheckState.PASS;null->CheckState.PENDING},rule.id in excluded,true)
        }
        vetoes.filter{it.selected&&it.state!=CheckState.PASS}.forEach{blockers+="排除區：${it.label}：${if(it.state==CheckState.FAIL)"已命中，移除" else "待查核"}"}
        if (active.isEmpty()) blockers += "請至少勾選一項條件"
        if(type==RadarType.G_MA5_WAVE&&!selected.containsAll(GPattern.REQUIRED_IDS))
            blockers += "G 入場至少須勾選 G1 大陽線與 G2 回踩，G3～G5 是持股管理"
        val score = if (active.isEmpty()) 0 else active.count { it.state == CheckState.PASS } * 100 / active.size
        val upgraded = type == RadarType.B_DEEP_REVERSAL && blockers.isEmpty() && metrics.technicalUpgrade
        val reasons = active.filter { it.state == CheckState.PASS }.map { it.label }.toMutableList()
        if (upgraded) reasons += "技術升級：RSI>50 且收盤站上20日線"
        val light = if (blockers.isNotEmpty()) SignalLight.NONE else if (upgraded) SignalLight.RED else SignalLight.ORANGE
        val evidence=listOfNotNull(
            if(type==RadarType.A_EARLY_BREAKOUT&&selected.any{it in APattern.IDS})metrics.aPattern.format() else null,
            if(type==RadarType.C_LONG_RED_VOLUME)CSignalEvidence.format(metrics,selected) else null,
            if(type==RadarType.D_MAIN_FORCE)metrics.dPattern.format() else null,
            if(type==RadarType.F_VOLUME_CONTRACTION)metrics.fPattern.format() else null,
            if(type==RadarType.G_MA5_WAVE)metrics.gPattern.format() else null,
            if(type==RadarType.E_DIF_TOUCH)metrics.ePattern?.format()?:"E 日K資料不足，待查核" else null,
            if(RsiGoldenCross.ID in selected)metrics.rsiGoldenCross?.format()?:"RSI(5/10) 資料不足，待查核" else null,
            if(BearishTurn.ID in excluded)metrics.bearishTurn?.format()?:"排除區證據不足，待查核" else null
        ).joinToString("\n").ifBlank{null}
        return SignalResult(s.code, s.name, s.sector, type, score, light, reasons, blockers, s, checks, upgraded,evidence,vetoes)
    }
    fun evaluateA(s: StockSnapshot, selected: Set<String> = defaults(RadarType.A_EARLY_BREAKOUT),excluded:Set<String> = emptySet()) = evaluate(s, RadarType.A_EARLY_BREAKOUT, selected,excluded)
    fun evaluateB(s: StockSnapshot, selected: Set<String> = defaults(RadarType.B_DEEP_REVERSAL),excluded:Set<String> = emptySet()) = evaluate(s, RadarType.B_DEEP_REVERSAL, selected,excluded)
    fun evaluateC(s: StockSnapshot, selected: Set<String> = defaults(RadarType.C_LONG_RED_VOLUME),excluded:Set<String> = emptySet()) = evaluate(s, RadarType.C_LONG_RED_VOLUME, selected,excluded)
    fun evaluateD(s: StockSnapshot, selected: Set<String> = defaults(RadarType.D_MAIN_FORCE),excluded:Set<String> = emptySet()) = evaluate(s,RadarType.D_MAIN_FORCE,selected,excluded)
    fun evaluateE(s: StockSnapshot, selected: Set<String> = defaults(RadarType.E_DIF_TOUCH),excluded:Set<String> = emptySet()) = evaluate(s,RadarType.E_DIF_TOUCH,selected,excluded)
    fun evaluateF(s: StockSnapshot, selected:Set<String> = defaults(RadarType.F_VOLUME_CONTRACTION), excluded:Set<String> = emptySet()) = evaluate(s,RadarType.F_VOLUME_CONTRACTION,selected,excluded)
    fun evaluateG(s: StockSnapshot, selected:Set<String> = defaults(RadarType.G_MA5_WAVE), excluded:Set<String> = emptySet()) = evaluate(s,RadarType.G_MA5_WAVE,selected,excluded)
    private fun defaults(type: RadarType) = ScanConditions.forRadar(type).filter { it.defaultEnabled }.map { it.id }.toSet()
}
