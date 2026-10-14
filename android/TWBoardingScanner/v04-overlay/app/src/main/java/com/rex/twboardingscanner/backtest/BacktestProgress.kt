package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.RadarType
import kotlin.math.ceil

/** One instance per worker attempt: stage timing never mixes downloads with CPU calculations. */
class BacktestProgress(private val settings:BtSettings) {
    private var phase=""
    private var phaseAt=0L
    private var lastDone=0
    private var lastTotal=0
    fun format(message:String,now:Long=System.nanoTime()/1_000_000):String {
        val match=Regex("(\\d+)\\s*/\\s*(\\d+)").find(message)
        val stage=when {
            message.startsWith("歷史日線")->"歷史資料下載"
            message.startsWith("依本次")->"條件掃描"
            message.startsWith("尾盤買入")->"交易模擬"
            else->""
        }
        val timing=if(stage.isEmpty()||match==null){
            phase="";"預估剩餘時間：準備資料，估算中…"
        }else {
            val done=match.groupValues[1].toIntOrNull()?:0
            val total=match.groupValues[2].toIntOrNull()?:0
            if(stage!=phase||total!=lastTotal||done<lastDone){phase=stage;phaseAt=now}
            lastDone=done;lastTotal=total
            val elapsed=(now-phaseAt).coerceAtLeast(0)
            when {
                total<=0->"$stage：估算中…"
                done>=total->"$stage：本階段完成"
                done<3||elapsed<1000->"$stage：預估剩餘時間估算中…"
                else->{
                    val seconds=ceil(elapsed.toDouble()/done*(total-done)/1000.0).toLong().coerceAtLeast(1)
                    "$stage：預估剩餘約 ${duration(seconds)}（本階段）"
                }
            }
        }
        return "本次執行：${activeLabels(settings)}\n$message\n$timing"
    }
    companion object {
        fun activeLabels(settings:BtSettings)=RadarType.entries
            .filter{it in settings.enabledRadars&&settings.rules[it].orEmpty().isNotEmpty()}
            .joinToString("、"){it.label}.ifBlank{"未啟用條件"}
        fun duration(seconds:Long):String=when{
            seconds>=3600->"${seconds/3600}時${seconds%3600/60}分${seconds%60}秒"
            seconds>=60->"${seconds/60}分${seconds%60}秒"
            else->"${seconds}秒"
        }
    }
}
