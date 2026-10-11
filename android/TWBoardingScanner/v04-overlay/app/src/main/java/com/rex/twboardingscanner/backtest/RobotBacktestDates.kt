package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.RuleMetrics
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * User-selected robot history window. Do not silently change an existing batch:
 * every date change belongs to a fresh session with its own market and signal snapshot.
 *
 * Matches the historical-backtest picker: completed dates only, earliest 2016,
 * and at most two years per test to keep the mobile download bounded.
 */
data class RobotBacktestDates(val start:LocalDate,val end:LocalDate){
    fun validated(today:LocalDate=LocalDate.now(RuleMetrics.TAIPEI)):RobotBacktestDates{
        require(start>=LocalDate.of(2016,1,1)){"起始日期不得早於 2016-01-01"}
        require(end<today){"結束日期必須早於今天；當日尚未收盤時資料不完整"}
        require(start<=end){"結束日期不可早於起始日期"}
        require(ChronoUnit.DAYS.between(start,end)<=730){"測試期間最多 730 天（約 2 年）"}
        return this
    }
    fun applyTo(settings:BtSettings)=settings.copy(start=start,end=end)
    companion object{
        fun of(settings:BtSettings)=RobotBacktestDates(settings.start,settings.end)
        fun parse(start:String,end:String)=RobotBacktestDates(LocalDate.parse(start.trim()),LocalDate.parse(end.trim()))
    }
}
