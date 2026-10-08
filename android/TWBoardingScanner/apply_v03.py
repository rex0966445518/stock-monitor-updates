from pathlib import Path

root = Path("_android_build/TWBoardingScanner_Android_v02")

# build.gradle
p = root/"app/build.gradle.kts"
s = p.read_text()
s = s.replace('versionCode = 2','versionCode = 3').replace('versionName = "0.2.0"','versionName = "0.3.0"')
if 'compileOptions {' not in s:
    s = s.replace('buildFeatures { viewBinding = true }',
'''buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }''')
p.write_text(s)

# Models
p = root/"app/src/main/java/com/rex/twboardingscanner/domain/Models.kt"
s = p.read_text()
s = s.replace('enum class RadarType { A_EARLY_BREAKOUT, B_DEEP_REVERSAL }',
              'enum class RadarType { A_EARLY_BREAKOUT, B_DEEP_REVERSAL, C_LONG_RED_VOLUME }')
s = s.replace('val price: Double,\n    val changePct: Double,',
              'val price: Double,\n    val openPrice: Double,\n    val highPrice: Double,\n    val lowPrice: Double,\n    val changePct: Double,')
p.write_text(s)

# Demo data
p = root/"app/src/main/java/com/rex/twboardingscanner/data/DemoDataProvider.kt"
s = p.read_text()
s = s.replace('price=1245.0, changePct=2.1,',
              'price=1245.0, openPrice=1208.0, highPrice=1252.0, lowPrice=1202.0, changePct=2.1,')
p.write_text(s)

# Scoring engine
p = root/"app/src/main/java/com/rex/twboardingscanner/domain/ScoringEngine.kt"
s = p.read_text()
if 'fun evaluateC' not in s:
    insert = r'''
    fun evaluateC(s: StockSnapshot): SignalResult {
        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        if (s.volumeLots < 500) blockers += "成交量低於500張"
        if (s.fundamentalsDeteriorating) blockers += "基本面明顯惡化"
        if ((s.fiveDayGainPct ?: 0.0) >= 15.0) blockers += "近5日漲幅過大"
        if (kotlin.math.abs(s.distanceFromMa20Pct ?: 0.0) >= 10.0) blockers += "距20MA乖離過大"
        if (s.kdOverheated || (s.rsi ?: 0.0) >= 75.0) blockers += "短線過熱"

        val bodyPct = if (s.openPrice > 0) (s.price - s.openPrice) / s.openPrice * 100.0 else 0.0
        val dayRange = (s.highPrice - s.lowPrice).coerceAtLeast(0.01)
        val closeNearHigh = ((s.highPrice - s.price) / dayRange) <= 0.30
        val volRatio = if (s.avg20VolumeLots > 0) s.volumeLots / s.avg20VolumeLots else 0.0
        val longRed = bodyPct >= 2.0 && s.changePct > 0.0
        val volumeBurst = volRatio >= 1.8

        var score = 0.0
        if (longRed) { score += 30; reasons += "長紅K實體>=2%" }
        if (volumeBurst) { score += 30; reasons += "成交量>=20日均量1.8倍" }
        else if (volRatio >= 1.5) score += 20
        if (closeNearHigh) { score += 15; reasons += "收盤接近當日高點" }
        if (s.price >= (s.ma5 ?: s.price)) score += 5
        if (s.price >= (s.ma20 ?: s.price)) { score += 5; reasons += "站上20MA" }
        if (s.difRising || s.macdGoldenCross || s.macdTurnedPositive || s.macdRedExpanding) { score += 10; reasons += "MACD同步改善" }
        if ((s.foreignToday ?: 0) > 0 || (s.foreign3d ?: 0) > 0 || (s.trust3d ?: 0) > 0) { score += 5; reasons += "法人籌碼加分" }

        val finalScore = score.toInt().coerceIn(0, 100)
        val hardPass = longRed && volumeBurst && closeNearHigh
        val light = when {
            blockers.any { it.contains("成交量") || it.contains("基本面") || it.contains("過熱") } -> SignalLight.NONE
            hardPass && finalScore >= 80 -> SignalLight.RED
            longRed && volRatio >= 1.5 && finalScore >= 65 -> SignalLight.ORANGE
            bodyPct >= 1.0 && volRatio >= 1.3 && finalScore >= 55 -> SignalLight.YELLOW
            else -> SignalLight.NONE
        }
        return SignalResult(s.code, s.name, RadarType.C_LONG_RED_VOLUME, finalScore, light, reasons, blockers, s)
    }
'''
    s = s.rsplit('\n}',1)[0] + '\n' + insert + '\n}\n'
p.write_text(s)

# MainActivity
p = root/"app/src/main/java/com/rex/twboardingscanner/ui/MainActivity.kt"
s = p.read_text()
s = s.replace('listOf("今日新觸發", "A｜起漲", "B｜深跌", "等待區", "已失效")',
              'listOf("今日新觸發", "A｜起漲", "B｜深跌", "C｜長紅爆量", "等待區", "已失效")')
s = s.replace('listOf(engine.evaluateA(s), engine.evaluateB(s))',
              'listOf(engine.evaluateA(s), engine.evaluateB(s), engine.evaluateC(s))')
s = s.replace('val bCount = all.count { it.radarType.name.startsWith("B_") && it.light != SignalLight.NONE }',
              'val bCount = all.count { it.radarType.name.startsWith("B_") && it.light != SignalLight.NONE }\n        val cCount = all.count { it.radarType.name.startsWith("C_") && it.light != SignalLight.NONE }')
s = s.replace('A型掃描：ON　B型掃描：ON\\n掃描股票：${filteredSource.size}/${source.size}　A:${aCount}　B:${bCount}　新觸發:${newTriggers}　最後更新:${now}',
              'A型掃描：ON　B型掃描：ON　C型掃描：ON\\n掃描股票：${filteredSource.size}/${source.size}　A:${aCount}　B:${bCount}　C:${cCount}　新觸發:${newTriggers}　最後更新:${now}')
s = s.replace('2 -> latest.filter { it.radarType.name.startsWith("B_") && it.light != SignalLight.NONE }\n            3 -> latest.filter { it.score in 65..84 }\n            4 -> latest.filter { it.light == SignalLight.WEAKENING }',
              '2 -> latest.filter { it.radarType.name.startsWith("B_") && it.light != SignalLight.NONE }\n            3 -> latest.filter { it.radarType.name.startsWith("C_") && it.light != SignalLight.NONE }\n            4 -> latest.filter { it.score in 65..84 }\n            5 -> latest.filter { it.light == SignalLight.WEAKENING }')
p.write_text(s)

# SignalAdapter
p = root/"app/src/main/java/com/rex/twboardingscanner/ui/SignalAdapter.kt"
s = p.read_text()
s = s.replace('val type = if (r.radarType.name.startsWith("A_")) "A型起漲" else "B型深跌反轉"',
'''val type = when {
            r.radarType.name.startsWith("A_") -> "A型起漲"
            r.radarType.name.startsWith("B_") -> "B型深跌反轉"
            else -> "C型長紅爆量"
        }''')
p.write_text(s)

# layout
p = root/"app/src/main/res/layout/activity_main.xml"
s = p.read_text().replace('A型掃描：ON　B型掃描：ON','A型掃描：ON　B型掃描：ON　C型掃描：ON')
p.write_text(s)

print("Applied v0.3 changes")
