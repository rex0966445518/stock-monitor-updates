package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.StockScope
import org.json.JSONObject

/** Converts the SAVED journal snapshot, never the mutable latest scanner draft. */
object BtRobotJournalSync{
    /** Archived checks become mandatory; unchecked checks remain free to explore.
     * No previous robot exclusions or draft settings may override this snapshot. */
    fun createBatch(store:BtRobotStore,settings:BtSettings,sourceJournalId:String):String {
        require(settings.stockScope==StockScope.ROBOT)
        return store.create(settings,required=BtRobotSpace.key(settings.rules),
            forbidden="0",sourceJournalId=sourceJournalId)
    }
    fun fromJournal(entry:JSONObject):BtSettings{
        require(entry.optString("state")=="DONE"){"只能同步已完成的回測日誌"}
        val archived=entry.optJSONObject("settings")?:error("這筆日誌沒有條件快照")
        require(archived.optJSONObject("rules")!=null&&archived.optJSONObject("stockPolicy")!=null&&archived.has("exitRules")){
            "舊日誌缺少完整 ABCDE、禁股或下車條件，無法保證同步所有資料"
        }
        val base=try{BtSettingsCodec.decode(archived)}catch(e:Exception){
            throw IllegalArgumentException("此日誌條件版本無法直接套用：${e.message}")
        }
        require(base.strategyVersion==4){"此日誌屬舊版交易模型，無法原樣套用到目前機器人"}
        require(base.marketGuardVersion>0){"此日誌未啟用大盤保護；目前機器人不能原樣套用無保護策略"}
        val robot=base.copy(stockScope=StockScope.ROBOT)
        BtRobotSpace.validate(robot)
        return robot
    }
}
