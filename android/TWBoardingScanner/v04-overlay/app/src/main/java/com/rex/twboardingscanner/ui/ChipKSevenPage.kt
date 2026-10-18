package com.rex.twboardingscanner.ui

/** Seven evidence categories requested by the user. Exact tab names vary by ChipK edition. */
enum class ChipKPage(
    val slug: String,
    val title: String,
    val aliases: List<String>
) {
    KLINE("kline", "K線", listOf("K線", "Ｋ線", "K線圖", "日K", "日Ｋ", "技術")),
    DAILY("chip_daily", "籌碼日報", listOf("籌碼日報", "籌碼日報表")),
    INSTITUTION("institution", "法人", listOf("法人", "三大法人", "法人籌碼")),
    LONG_SHORT("bull_bear", "多空", listOf("多空", "多空分析", "多空指標")),
    MAIN_FORCE("main_force", "主力", listOf("主力", "主力進出", "主力買賣超")),
    WHALES("whales", "大戶", listOf("大戶", "大戶持股", "大戶籌碼")),
    FINANCIAL("financial", "財報", listOf("財報", "財務", "財務報表", "基本面"));

    val number: Int get() = ordinal + 1

    companion object {
        val ordered: List<ChipKPage> = entries.toList()
        fun at(index: Int): ChipKPage? = ordered.getOrNull(index)
    }
}

/** Read-only semantic snapshot of a visible navigation control, not arbitrary page content. */
data class ChipKTabNode(
    val key: Int,
    val label: String,
    val resourceId: String = "",
    val clickable: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val top: Int = 0,
    val bottom: Int = 0,
    val screenHeight: Int = 2000
)

sealed class ChipKPageDecision {
    data class Tap(val key: Int): ChipKPageDecision()
    data class Scroll(val key: Int): ChipKPageDecision()
    data class Confirm(val evidence: String): ChipKPageDecision()
    data object Wait: ChipKPageDecision()
}

/** Narrow, non-transactional tab navigation. If a target can't be confirmed, do not capture it. */
object ChipKPageNavigator {
    private val unsafe = Regex(
        "下單|交易密碼|委託|買入|賣出|登入|付款|確認購買|order|sell|buy",
        RegexOption.IGNORE_CASE
    )
    private fun norm(raw: String) =
        raw.replace(" ", "").replace("\n", "").replace("　", "").replace("Ｋ", "K").trim()

    /** Require full visible label matches; never click a paragraph or a brokerage table row. */
    fun matches(page: ChipKPage, label: String): Boolean {
        val name = norm(label)
        if (name.isEmpty() || name.length > 12 || unsafe.containsMatchIn(name)) return false
        return page.aliases.any { norm(it) == name }
    }

    /**
     * For navigators exposing selected state, this is an authoritative confirmation.
     * For custom/WebView tabs the accessibility service may use a content-change fallback,
     * which must be explicitly labelled "unverified" in the saved report.
     */
    fun decide(
        page: ChipKPage,
        controls: List<ChipKTabNode>,
        clicked: Boolean,
        screenChangedAfterClick: Boolean,
        pageSettled: Boolean,
        scrollCount: Int
    ): ChipKPageDecision {
        val candidates = controls.filter {
            it.clickable && matches(page, it.label) &&
                it.top >= 0 && it.top <= (it.screenHeight * 0.75).toInt()
        }
        if (candidates.any { it.selected || it.checked }) {
            return ChipKPageDecision.Confirm("selected_tab")
        }
        if (clicked) {
            return if (screenChangedAfterClick && pageSettled && candidates.isNotEmpty())
                ChipKPageDecision.Confirm("changed_unverified")
            else ChipKPageDecision.Wait
        }
        candidates.firstOrNull()?.let { return ChipKPageDecision.Tap(it.key) }
        if (scrollCount >= 5) return ChipKPageDecision.Wait
        val scroller = controls.firstOrNull {
            it.scrollable && it.top >= 0 && it.bottom < it.screenHeight * 0.75 &&
                it.bottom - it.top in 25..450
        }
        return scroller?.let { ChipKPageDecision.Scroll(it.key) } ?: ChipKPageDecision.Wait
    }
}
