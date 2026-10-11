package com.rex.twboardingscanner.backtest

/** Per-batch money/risk inputs; never mutate an existing completed or running result. */
data class RobotTradingParams(val capital:Double,val maxHoldings:Int,val targetPct:Double){
    fun applyTo(base:BtSettings)=base.copy(capital=capital,maxHoldingStocks=maxHoldings,targetNetPct=targetPct)
    companion object{
        fun of(s:BtSettings)=RobotTradingParams(s.capital,s.maxHoldingStocks?:25,s.targetNetPct)
        fun parse(capital:String,holding:String,target:String):RobotTradingParams{
            val cash=capital.trim().replace(",","").replace("，","").toDoubleOrNull()
            require(cash!=null&&cash.isFinite()&&cash>0){"初始本金請輸入大於 0 的金額（元）"}
            val limit=holding.trim().toIntOrNull()
            require(limit!=null&&limit>0){"最高持倉請輸入大於 0 的整數（檔）"}
            val rate=target.trim().removeSuffix("%").toDoubleOrNull()
            require(rate!=null&&rate.isFinite()&&rate in 0.01..1000.0){"淨利下車請輸入 0.01～1000 的百分比"}
            return RobotTradingParams(cash,limit,rate)
        }
    }
}
