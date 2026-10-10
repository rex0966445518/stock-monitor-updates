package com.rex.twboardingscanner.backtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object BtSettingsCodec {
    fun capture(c:Context,start:LocalDate,end:LocalDate,capital:Double,codes:String):BtSettings {
        val prefs=c.getSharedPreferences("scanner_filters",0)
        val rules=com.rex.twboardingscanner.domain.RadarType.entries.associateWith { type ->
            val all=com.rex.twboardingscanner.domain.ScanConditions.forRadar(type)
            prefs.getStringSet("rules_${com.rex.twboardingscanner.domain.ScanConditions.VERSION}_${type.name}",null)?.intersect(all.map{it.id}.toSet())
                ?:all.filter{it.defaultEnabled}.map{it.id}.toSet()
        }
        val allSectors=com.rex.twboardingscanner.domain.StockSector.entries.toSet()
        val saved=prefs.getStringSet("enabled_sectors",null)
        val sectors=saved?.mapNotNull {name->allSectors.firstOrNull{it.name==name}}?.toMutableSet()?:allSectors.toMutableSet()
        if(saved!=null&&allSectors.filter{it!=com.rex.twboardingscanner.domain.StockSector.UNKNOWN}.all{it.name in saved})sectors.add(com.rex.twboardingscanner.domain.StockSector.UNKNOWN)
        return BtSettings(start,end,capital,codes,rules.mapValues{it.value.toSet()},sectors.ifEmpty{allSectors}.toSet(),stockPolicy=com.rex.twboardingscanner.data.StockPolicyStore(c).read(),exitRules=com.rex.twboardingscanner.data.ExitRuleStore(c).read(com.rex.twboardingscanner.domain.StockScope.BACKTEST))
    }
    fun encode(s:BtSettings):JSONObject {
        s.validateTrading()
        val target=btPercent(s.targetNetPct)
        return JSONObject().put("strategyVersion",s.strategyVersion).put("rulesVersion",com.rex.twboardingscanner.domain.ScanConditions.VERSION)
        .put("start",s.start.toString()).put("end",s.end.toString()).put("capital",s.capital).put("codes",s.codes)
        .put("exitRules",com.rex.twboardingscanner.domain.ExitRules.json(s.exitRules)).put("exitRuleSummary",com.rex.twboardingscanner.domain.ExitRules.summary(s.exitRules))
        .put("stockPolicy",s.stockPolicy.json()).put("stockScope",s.stockScope.name)
        .put("targetNetPct",s.targetNetPct).put("maxHoldingStocks",s.maxHoldingStocks?:JSONObject.NULL)
        .put("holdingLimitBasis","不同股號計算檔數；同股不同日可加買一張；先賣後買；名額及資金競爭按股號排序")
        .put("rules",JSONObject().apply{s.rules.forEach{(type,ids)->put(type.name,JSONArray(ids.sorted()))}})
        .put("sectors",JSONArray(s.sectors.map{it.name}.sorted()))
        .put("sectorLabels",JSONArray(s.sectors.sortedBy{it.ordinal}.map{it.label}))
        .put("ruleLabels",JSONObject().apply{s.rules.forEach{(type,ids)->put(type.name,JSONArray(com.rex.twboardingscanner.domain.ScanConditions.forRadar(type).filter{it.id in ids}.map{it.label}))}})
        .put("rebateModel",if(s.strategyVersion>=3)JSONObject().put("threshold",50000000).put("lowRate",0.0005).put("highRate",0.001).put("basis","每曆月買賣成交總額；超過門檻全月適用高率；未滿月依截止日累計估計，不加入可用資金")else JSONObject.NULL)
        .put("breakEvenAfterCalendarDays",if(s.strategyVersion>=3)5 else JSONObject.NULL)
        .put("strategyLabel",(s.maxHoldingStocks?.let{"最高持倉 $it 檔（不同股號）；"}?:"持倉檔數不限；")+if(s.strategyVersion>=3)"尾盤各買 1,000 股；隔日起扣買賣費稅淨利 $target% 賣出；持有超過 5 個日曆日改以扣費稅保本出場，虧損續抱（另有下車條件時依快照判斷）。每月買賣合計超過 5,000 萬折讓 0.1%，否則 0.05%；估計應收折讓金納入總損益但不再投資。" else "尾盤各買 1,000 股；隔日起扣買賣費稅淨利 ≥ $target% 才賣出；未達標續抱，截止日保留持倉。")
        .put("costModel",JSONObject().put("buyFeeRate",0.001425).put("sellFeeRate",0.001425).put("minimumFee",20).put("sellTaxRate",0.003).put("slippageRate",0.001))
    }
    fun decode(o:JSONObject):BtSettings {
        require(o.getInt("strategyVersion") in 2..4&&o.getString("rulesVersion")==com.rex.twboardingscanner.domain.ScanConditions.VERSION){"回測條件版本已變更，請重新開始"}
        val rules=com.rex.twboardingscanner.domain.RadarType.entries.associateWith{type->val a=o.getJSONObject("rules").getJSONArray(type.name);(0 until a.length()).map{a.getString(it)}.toSet()}
        rules.forEach{(type,ids)->require(ids.all{id->com.rex.twboardingscanner.domain.ScanConditions.forRadar(type).any{it.id==id}})}
        val a=o.getJSONArray("sectors");val sectors=(0 until a.length()).map{com.rex.twboardingscanner.domain.StockSector.valueOf(a.getString(it))}.toSet()
        val version=o.getInt("strategyVersion")
        // Older frozen runs retain unlimited holdings and the original 3% target.
        val limit=if(version>=4){
            val n=o.getDouble("maxHoldingStocks")
            require(n.isFinite()&&n>=1&&n<=Int.MAX_VALUE&&n==kotlin.math.floor(n)){"持倉上限不是有效整數"}
            n.toInt()
        }else null
        return BtSettings(LocalDate.parse(o.getString("start")),LocalDate.parse(o.getString("end")),o.getDouble("capital"),o.getString("codes"),rules,sectors,version,limit,if(version>=4)o.getDouble("targetNetPct")else 3.0,com.rex.twboardingscanner.domain.StockPolicy.read(o.optJSONObject("stockPolicy")),com.rex.twboardingscanner.domain.StockScope.valueOf(o.optString("stockScope","BACKTEST")),com.rex.twboardingscanner.domain.ExitRules.read(o.optJSONArray("exitRules"))).also{it.validateTrading()}
    }
}


class BacktestWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val store=BacktestStore(applicationContext);val runId=inputData.getString("id")?:return Result.failure()
        return try {
            setForegroundAsync(notification()).get()
            check(store.active()==runId&&store.state()=="RUNNING"){"此回測已停止或歸零"}
            val snapshot=inputData.getString("settings")?.let{JSONObject(it)}?:store.log(runId)?.optJSONObject("settings")?:error("回測條件快照不存在，請重新開始")
            val s=BtSettingsCodec.decode(snapshot)
            require(s.start<=s.end&&s.end<LocalDate.now(com.rex.twboardingscanner.domain.RuleMetrics.TAIPEI))
            require(java.time.temporal.ChronoUnit.DAYS.between(s.start,s.end)<=730)
            val cancel={isStopped||store.active()!=runId||store.state()!="RUNNING"||com.rex.twboardingscanner.data.StockPolicyStore(applicationContext).read()!=s.stockPolicy}
            val progress={message:String->check(!cancel()){ "已取消回測" };store.update(runId,message)}
            val data=BacktestData(applicationContext).load(s,progress,cancel)
            val prepared=BacktestEngine.prepare(data.series,s,progress,cancel)
            val run=BacktestEngine.run(prepared,s,progress,cancel)
            check(!cancel()){ "已取消回測" }
            val note="現存上市櫃與目前產業分類，存在樣本／存活偏差；拆併股原始價未核對者排除。已勾 EPS／財報／法人缺少歷史證據時視為待查核，不可入選。A/B 排除當日日線，C 包含當日；C 完整收盤訊號與同收盤成交屬理想化假設，可能高估績效。日線不提供精確盤中成交時刻；13:30／09:00 皆為模型時間，觸價只記錄時段。"+if(s.strategyVersion>=3)"股息與折讓金列應收，不再投資；持有超過 5 個日曆日可扣費稅保本賣出，仍虧損則續抱，已設定下車條件者以前一完整日線判斷並於下一可交易日開盤模擬賣出；截止日不強制平倉。"else"舊策略：股息列應收，不再投資；未達淨利 3% 續抱，截止日不強制平倉。"
            check(store.save(runId,BtResult(s,run,data.requested,data.series.size,data.excluded,note,prepared.pendingChecks))){"此回測已停止或歸零"};Result.success()
        }catch(e:Exception){store.update(runId,if(isStopped)"已取消；未完成結果不列為績效" else "回測中止：${e.message}",if(isStopped)"CANCELED" else "ERROR");Result.failure()}
    }
    private fun notification():ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("backtest","歷史回測",NotificationManager.IMPORTANCE_LOW))
        val n=NotificationCompat.Builder(applicationContext,"backtest").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("台股歷史回測執行中").setContentText("正在下載及計算歷史資料；可回 App 查看進度或取消").setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(4201,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4201,n)
    }
}
