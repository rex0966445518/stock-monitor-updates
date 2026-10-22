# AI-離職神器｜全 App 紫色霓虹 UI v2 設計與改版規格

- 文件版本：v1.0（2026-10-22）
- Repository：[rex0966445518/stock-monitor-updates](https://github.com/rex0966445518/stock-monitor-updates)
- Android 專案：`android/TWBoardingScanner/v04-overlay`
- 前置 UI 首頁 PR：[ #23 — 紫色霓虹首頁](https://github.com/rex0966445518/stock-monitor-updates/pull/23)
- 更新器修正候選 PR：[ #22 — v0.5.2 同版本碼更新修正](https://github.com/rex0966445518/stock-monitor-updates/pull/22)
- 設計來源：使用者提供的 AI-離職神器紫黑霓虹首頁參考圖，以及後續確認的主掃描／設定／回測／機器人／更新／股票分析六張設計概念圖。
- **狀態**：設計與實作規格；不代表所有內頁程式已改完、已合併或最新 APK 已發行。

## 1. 改版目的與邊界

**交付結果：所有可見頁面、設定子頁、彈窗、表格、圖表卡、載入／錯誤／空白狀態均使用同一套視覺語言，沒有「首頁新、點進去舊」的落差。**

只修改 UI 表達及必要的導航適配，預設不改掃描條件公式、回測計算、機器人排程／中斷恢復、更新器驗證與安裝邏輯、已保存設定與歷史資料結構。功能變更必須另列、另做測試，不能混在純換皮 PR 中。

設計稿數字及股票案例**僅是示意**；正式 App 的股價、成交量、勝率、報酬、掃描進度、5/6、6/6、日期、版本號都必須來自既有資料源或明確標記「示範／無資料」，不得硬編數字。報價需顯示來源及更新時間，回測無數據顯示「尚無回測結果」，不得憑空生成曲線。條件 A–G 的頁面短標必須對照目前實際 `RadarType` 定義，不可照設計稿錯誤更改條件意義：

| 策略 | 目前程式實際名稱 | 建議色票 |
|---|---|---|
| A | 準備起漲 | 紫 `#A83BFF` |
| B | 深跌反轉 | 藍 `#2E88FF` |
| C | 突發爆量 | 玫紅 `#FF4E9D` |
| D | 主力進場 | 金 `#F2B151` |
| E | DIF金叉 | 青綠 `#24D6C6` |
| F | 縮量上漲 | 亮藍 `#4E7DFF` |
| G | 大陽線・5日線波段 | 洋紅 `#EA4FD2` |

## 2. 視覺 Design Tokens

| Token | 建議值 | 使用場景 |
|---|---|---|
| `bg/root` | `#0B041F` | Activity 全頁底色 |
| `bg/surface` | `#201043` | 次層卡片、表單 |
| `bg/surfaceTop` | `#36146E` | 卡片頂端漸層 |
| `brand/primary` | `#B443FF` | 主要 CTA／導覽選中態 |
| `brand/pink` | `#FF4AB8` | 強調及台股上漲提示 |
| `brand/blue` | `#3B86FF` | 技術數據、次要 CTA |
| `semantic/success` | `#53E3BA` | 完成／正確／有資料 |
| `semantic/warning` | `#F2B65D` | 待查核／警示 |
| `semantic/danger` | `#FF609C` | 錯誤／停止；漲跌語意必須依既有台股慣例處理 |
| `text/primary` | `#F8F3FF` | 主文字、數值 |
| `text/secondary` | `#C4AEEB` | 輔助文字 |
| `border/neon` | `#A247F4` | 卡片邊線及 focus |
| `radius/card` | 18–22dp | 大面板 |
| `radius/cta` | 14–18dp | 按鈕 |
| `spacing` | 4 / 8 / 12 / 16 / 24dp | 全頁間距 |

**色彩與可讀性**：大面積螢光只當裝飾，不讓霓虹圖案穿過文字；內文及數據至少符合 WCAG AA 對比度（一般文字 ≥4.5:1，大字 ≥3:1）；狀態不可只靠顏色判斷，應同時有文字／圖示。不可將整張設計圖當成背景製造假按鈕。

**字體建議**：大頁標 24–30sp，區塊標 16–20sp，卡片標 14–16sp，重要數字 22–32sp，說明文字 12–14sp。支援系統字體放大、字數換行，不能固定高度裁掉內容。可觸控目標原則 ≥48dp。

**材質與效能**：用可重用 drawable、`GradientDrawable`、`NeonUi` 元件，不依賴重度模糊或無限持續 Canvas 動畫；山景／K 線只在 header/背景的低對比區域，避免遮擋資訊。切換畫面不應中斷正在進行的背景掃描／回測服務。

## 3. 資訊架構與導航規則

主導航五個入口：**首頁、主掃描、歷史回測、自動測試機器人、一鍵更新**。導航點擊到現有 Activity/事件；子畫面提供清晰「返回上一頁」及「回首頁」，不可先銷毀工作狀態。只有主頁可以較高比例展示 Logo 背景；工作頁優先保留資訊密度。

目前程式入口與來源（PR #23 為首頁雛形）：

| 畫面／任務 | 實際程式範圍 | 對應需求與驗收 |
|---|---|---|
| 首頁 | `ui/NeonHomeDashboard.kt`（PR #23）、`ui/MainActivity.kt` | Logo／導航／真實統計／最近訊息 |
| 主掃描 | `ui/MainActivity.kt`, `res/layout/activity_main.xml`, `ui/ResultFilterBar.kt`, `ui/SignalAdapter.kt`, `res/layout/item_stock_signal.xml` 和 `layout-w400dp` | 即時進度／A–G／結果列表／排序／停止與恢復；掃描服務行為不變 |
| 條件與工具 | `ui/MainActivity.kt` 之 `showSettings()`、`DPatternEditor.kt`, `EPatternEditor.kt`, `FPatternEditor.kt`, `GPatternEditor.kt`, `ExclusionEditor.kt`, `ExitRulesEditor.kt`, `RuleNumberFields.kt`, `RobotSectorDialog.kt` | 彩色七策略卡、開關、數值設定、儲存狀態、編輯頁一致 |
| 回測 | `ui/BacktestActivity.kt`, `BacktestJournalActivity.kt`, `BacktestProfitPanel.kt`, `BacktestFiveMinutePanel.kt`, `BacktestTradeCard.kt`, `BacktestDailyCard.kt` | 控制區、日期區、真實績效、圖表與日誌，無數據顯示空狀態 |
| 機器人 | `ui/BtRobotActivity.kt`, `BtRobotHistoryActivity.kt`, `BtRobotRulesDialog.kt`, `BtSnapshotDialog.kt`, `RobotScanView.kt` | 活動任務狀態、規則、進度、歷史、錯誤、可取消與恢復 |
| 更新 | `ui/AppUpdateActivity.kt`; 更新邏輯由 `update/` 套件提供 | 版號、檔案與簽章驗證、下載／安裝步驟、錯誤與重試，不篡改版本比較 |
| 個股／資訊 | `ui/StockChartDialog.kt`, `FinancialDialog.kt`, `FinancialPanelView.kt`, `MiniStockChartView.kt`, `WantGooInlineChartView.kt` | 價格／K線／量／評分／風險／更新時間；點擊返回不掉結果 |
| 結果與歷史 | `ui/ScanArchiveActivity.kt`, `HistoryAdapter.kt`, `res/layout/item_history_signal.xml` | 分類、追蹤、封存及歷史卡片 |
| 模擬交易／工具 | `ui/PaperTradingActivity.kt`, `PaperStrategyActivity.kt`, `StockToolsActivity.kt`, `TradeSideVolumeImportActivity.kt` | 保留設定、模擬持倉、匯入 CSV、禁股限價與導航 |
| 通用彈窗 | `ui/NeonUi.kt`, `ui/NeonBackdrop.kt`, `res/values/themes.xml`, `res/drawable/` | Dialog/BottomSheet、Loading/Error/Empty、文字欄位、選取器一致 |

> 表內是現有程式檔及規劃責任區；不是每個規劃畫面已存在完整的設計稿所示功能。沒有資料源、尚未實作的卡片／圖表不得當成正式功能宣稱。

## 4. 核心畫面規格

### 4.1 首頁
含品牌 Hero、四個主要功能入口、掃描統計、七組策略、工具快速入口、最近活動；首頁數值在實際資料缺失時顯示 `—`；不應把股票/日期/百分比模板寫死。PR #23 為起點。

### 4.2 主掃描
頂部固定掃描狀態（已處理 / 總數、目前步驟、完成百分比、掃描耗時／心跳），下一層為開始／停止／背景運作快捷；策略條件 A–G 可看到啟用、進入設定。結果列表按來源日期、更新時間、實際檢查狀態呈現，卡片含股號、名稱、價格、漲跌、條件、資料不足／排除原因。5/6、6/6 **僅對確實有六項可比較條件的數據計算**，絕不跨不同條件數混算。

### 4.3 條件與工具
七個策略入口均可編輯，對應開關只影響該策略的啟用狀態；對話框退出應維持保存語義。估量比、漲幅、迴轉率等僅顯示既有選項或確定新增並經驗證的欄位，不可假裝所有條件都有相同欄位。背景診斷、權限、CSV、禁股／限價、賣出規則等入口不得遺漏。

### 4.4 回測／機器人
先顯示進度、條件、日期、數據來源及狀態，再顯示實際測得績效；不存在的權益曲線／月度圖不得生造。長任務在前景／背景之間切換時保留 checkpoint，舊日誌與結果可追溯。所有「取消／重跑／清除」確認對話框統一風格但保留二次確認與原本保護機制。

### 4.5 更新
從現有 `AppUpdateStore`、`AppUpdateWorker` 讀狀態，UI 提供：檢查、下載、驗證、準備安裝／Android 安裝確認、錯誤／重試。不可宣稱沒有通過的 APK 或簽章已驗證。PR #22 與 #23 合併前要先處理版本碼與功能衝突，不能在本次 UI 升級中刪除更新防護。

### 4.6 個股／股票工具／封存／模擬交易
維持現有資料來源、匯入及操作事件；彩色策略標籤／時間戳與風險提示必須由資料狀態驅動。外部 K 線或籌碼 App 的跳轉屬現有業務功能，不因 UI 重製更改授權或權限流程。

## 5. 共用元件（必須先完成）

1. `NeonUi` Token 定義；`Theme.TWBoardingScanner` 色票、選取態、焦點態。
2. `NeonBackdrop` 輕量靜態山巒／K 線背景。
3. 具有一致字級、描邊、圓角的 Header、Section、Card、CTA、Tab、Badge、Empty、Loading、Error。
4. 可重用 A–G 策略卡與不同狀態（啟用／停用／選中／資料不足）。
5. 真實資料綁定狀態型別，不以樣稿示例數字作預設值。
6. 共用錯誤提示與超時重試樣式；保留 Android 系統權限／安裝對話框不可客製的部分。

## 6. 驗收／測試矩陣

- 單元測試：A～G 七組開關與編輯入口、掃描狀態與 5/6／6/6 正確性、歷史內容保留、版本頁顯示 `current/latest`、原本更新安全測試。
- UI 測試：以 360dp、400/411dp、平板寬度至少一組；字體縮放 1.0、1.3、1.5；深色系，狀態列／系統導航內距完整，卡片無水平裁切。
- Screenshot regression：首頁、主掃描、設定 G、回測、機器人、更新、單股詳情、空白／錯誤／載入、BottomSheet、Dialog。
- 訪問性：TalkBack 有名稱與操作狀態，觸控大小 ≥48dp，對比度合理；焦點順序符合操作流程。
- 行為：掃描開始／停止／返回／切換別的 App、持續任務、回測續跑、機器人 checkpoint、CSV 匯入、歷史紀錄、更新與 APK 驗證均不退化。
- CI：GitHub `Build Android APK` 的 `testDebugUnitTest` 與 `assembleDebug` 必須成功；PR 用 review APK 先檢查，只有合併 main 且 GitHub Release 發布與公開下載驗證成功才宣布「正式版可一鍵更新」。
- 提供實際 Android emulator/真機截圖；以 UI 截圖與參考圖逐頁對照並檢查使用者操作，不以設計概念圖取代實機驗收。

## 7. 發布與回退

推薦以 `PR #23 → 基礎共用樣式 → 個別模組 PR → 全頁驗收 → 正式 main` 小步遞進。每個 PR 必須清楚標示變更畫面、來源檔、測試、實機截圖與未完成事項。正式發布前核對版本碼、套件名、簽章、更新 JSON、固定下載位置；**不得單因 review APK 編譯成功就宣稱 latest-android 已替換**。Release 發布不成功時保留舊版，對應分支可回退。

## 8. 完成的定義（Definition of Done）

- [ ] 所有主要頁面與子頁採用相同 Token／卡片／Header／背景／按鈕／狀態元件。
- [ ] 無舊式頁面及不一致彈窗；主功能每個入口點進去仍為新版視覺。
- [ ] A–G 可設定、可啟用、可保存，G 在主掃描／回測／機器人都可見。
- [ ] 所有數字由真實資料提供；沒有示範資料假裝最新市場資料。
- [ ] 背景掃描、回測、機器人、股票跳轉、更新功能無回歸。
- [ ] 各種螢幕寬度、字體放大、空資料及錯誤狀態均通過測試。
- [ ] APK 正式發布、下載 SHA/簽章驗證及 App 一鍵更新確認完成。

追蹤文件：[全 App UI v2 實作任務清單](NEON_V2_IMPLEMENTATION_TASKS.md)。
