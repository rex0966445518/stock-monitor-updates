package com.rex.twboardingscanner.backtest

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.rex.twboardingscanner.domain.RadarType
import org.json.JSONObject

/** Classify the predicates actually tested, rather than every enabled but unused region. */
internal object RobotHistoryCategory {
    fun bit(type:RadarType)=1 shl type.ordinal
    fun mask(settings:JSONObject,key:String,summary:JSONObject):Int {
        val active=settings.optJSONArray("enabledRadars")?.let{a->(0 until a.length()).mapNotNull{runCatching{RadarType.valueOf(a.getString(it))}.getOrNull()}.toSet()}?:defaultActiveRadars()
        val baseline=summary.optBoolean("baseline")||settings.optBoolean("robotBaseline")
        val rules=if(key.startsWith("grid_"))null else runCatching{BtRobotSpace.rules(key)}.getOrNull()
        return active.fold(0){mask,type->
            val hasRules=rules?.get(type)?.isNotEmpty()?:((settings.optJSONObject("rules")?.optJSONArray(type.name)?.length()?:0)>0)
            if(baseline||hasRules)mask or bit(type) else mask
        }
    }
    /** Upgrade in bounded pages, without decoding compressed trade reports. */
    fun backfill(db:SQLiteDatabase){
        var after=0L
        while(true){
            val rows=mutableListOf<Pair<Long,ContentValues>>()
            db.rawQuery("SELECT t.n,t.key,t.summary,s.json FROM trials t LEFT JOIN sessions s ON s.id=t.session WHERE t.n>? ORDER BY t.n LIMIT 200",arrayOf(after.toString())).use{cursor->while(cursor.moveToNext()){
                val parsed=BtTrialSummary.read(cursor.getString(2))
                val summary=parsed?:JSONObject()
                val settings=if(cursor.isNull(3))JSONObject() else runCatching{JSONObject(cursor.getString(3)).optJSONObject("settings")}.getOrNull()?:JSONObject()
                val profit=summary.optDouble("profit",0.0).takeIf{it.isFinite()}?:0.0
                rows+=cursor.getLong(0) to ContentValues().apply{
                    put("radars",mask(settings,cursor.getString(1),summary));put("net_profit",profit);put("summary_valid",if(parsed==null)0 else 1)
                }
            }}
            if(rows.isEmpty())return
            rows.forEach{(n,values)->db.update("trials",values,"n=?",arrayOf(n.toString()))}
            after=rows.last().first
        }
    }
}
