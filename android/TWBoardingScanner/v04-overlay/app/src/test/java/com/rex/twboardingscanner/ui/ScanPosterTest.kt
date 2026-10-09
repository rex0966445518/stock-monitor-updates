package com.rex.twboardingscanner.ui
import android.graphics.BitmapFactory
import com.rex.twboardingscanner.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanPosterTest {
    @Test fun officialCodesAreClassifiedAndUnknownIsDistinct() {
        assertEquals(StockSector.SEMICONDUCTOR,StockSector.fromIndustry("24"))
        assertEquals(StockSector.ELECTRONICS,StockSector.fromIndustry("28"))
        assertEquals(StockSector.AI_SERVER,StockSector.fromIndustry("25"))
        assertEquals(StockSector.FINANCE,StockSector.fromIndustry("17"))
        assertEquals(StockSector.ELECTROMECHANICAL,StockSector.fromIndustry("05"))
        assertEquals(StockSector.BIOTECH,StockSector.fromIndustry("22"))
        assertEquals(StockSector.OTHER,StockSector.fromIndustry("20"))
        assertEquals(StockSector.UNKNOWN,StockSector.fromIndustry(""))
        assertEquals(StockSector.UNKNOWN,StockSector.fromIndustry("99"))
        assertEquals(StockSector.SEMICONDUCTOR,StockSector.fromIndustry("半導體業"))
    }
    @Test fun exportUsesOnlyCurrentListAndLatestStatePerRadar() {
        val today=LocalDate.now(RuleMetrics.TAIPEI)
        val stock=MarketStock("1234","測試公司",Market.TWSE,StockSector.SEMICONDUCTOR,100.0,106.0,99.0,104.0,4.0,2000,null,null)
        val bars=(0..30).map {DailyBar(today.minusDays((31-it).toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),100.0,106.0,99.0,104.0,2000000)}
        val snap=TechnicalCalculator().build(stock,bars)
        val signal=ScoringEngine().evaluateA(snap,setOf("price","volume"))
        val newer=signal.copy(snapshot=snap.copy(timestamp=snap.timestamp+1,price=105.0))
        val rejected=signal.copy(code="9999",light=SignalLight.NONE)
        val result=ScanPoster.collect(listOf(newer,signal,rejected))
        assertEquals(3,result.size);assertEquals(1,result.getValue(RadarType.A_EARLY_BREAKOUT).size)
        assertEquals(105.0,result.getValue(RadarType.A_EARLY_BREAKOUT).single().price,0.001)
        assertTrue(result.getValue(RadarType.B_DEEP_REVERSAL).isEmpty())
        assertTrue(ScanPoster.collect(emptyList()).values.all { it.isEmpty() })
        // A newer rejected result must not resurrect an earlier qualifying result.
        val removed=newer.copy(light=SignalLight.NONE,snapshot=newer.snapshot.copy(timestamp=snap.timestamp+2))
        assertTrue(ScanPoster.collect(listOf(signal,removed)).values.all { it.isEmpty() })
    }
    @Test fun streamedPngRendersAllRowsAndEmptyState() {
        val folder=File("build/ui-previews");folder.mkdirs()
        val day=LocalDate.of(2026,10,8)
        val bars=(0..89).map { i -> val close=80.0+i*.29+kotlin.math.sin(i*.7)*1.4
            DailyBar(day.minusDays((89-i).toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close-.6,close+1.1,close-1.5,close,800000L+i*19000) }
        val checks=listOf("macd" to "MACD起轉","heat" to "未過熱","converge" to "三線收斂","ma5up" to "5日線翻揚","above3" to "站上三線","rsi" to "RSI>50").map {ConditionCheck(it.first,it.second,CheckState.PASS,true,false)}
        val stock=PosterStock("1234","版面測試公司", "半導體",RadarType.A_EARLY_BREAKOUT,104.5,3.42,0,bars,100,checks,ClosingAuction("2026-10-08","13:30:00",268,105.0,106.0,"紅綠依收盤前成交價"))
        RadarType.entries.forEachIndexed { i,type ->
            val list=if(i==2) emptyList() else List(i+3) {stock.copy(code="${1234+it}")}
            val file=File(folder,"poster-${('A'.code+i).toChar()}.png")
            ScanPoster.write(file,type,list,"2026-10-09","12:30",false,listOf(3,4,0),1950,1950)
            val decoded=BitmapFactory.decodeFile(file.path)
            assertNotNull(decoded);assertEquals(1080,decoded.width)
            assertEquals(maxOf(1540,890+350*list.size),decoded.height);decoded.recycle()
        }
        val file=File.createTempFile("poster-many",".png")
        ScanPoster.write(file,RadarType.A_EARLY_BREAKOUT,List(100){stock},"2026-10-09","12:30",true)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
        assertEquals(35890,bounds.outHeight);file.delete()
    }
}
