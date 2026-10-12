package com.rex.twboardingscanner.backtest

/** Fixed parameter grids need a real seed rule set; free rule exploration can start empty. */
object RobotRuleSelection {
    fun prepare(settings:BtSettings,fixedRules:Boolean):BtSettings {
        require(settings.enabledRadars.isNotEmpty()){ "請至少啟用 A／B／C／D／E 中的一區" }
        if(!fixedRules)return settings
        val defaults=defaultBtRules()
        val prepared=settings.copy(rules=settings.rules.mapValues{(type,rules)->
            if(type in settings.enabledRadars&&rules.isEmpty())defaults.getValue(type) else rules
        })
        require(prepared.enabledRadars.all{prepared.rules[it].orEmpty().isNotEmpty()}){
            "啟用區域缺少固定條件，請開啟該區條件選單設定"
        }
        return prepared
    }
}
