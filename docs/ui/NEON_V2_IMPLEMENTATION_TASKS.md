# AI-離職神器｜全 App UI v2 GitHub 實作任務清單

參照：[全 App 紫色霓虹 UI v2 規格書](NEON_V2_DESIGN_SPEC.md)｜既有首頁 [PR #23](https://github.com/rex0966445518/stock-monitor-updates/pull/23)。

**狀態說明**：\`[ ]\` 未驗收、\`[x]\` 已完成並有可查測試證據。不要把設計稿完成視為程式實作完成。

## P0 共用基礎（先做，避免重工）
- [ ] 將 \`NeonUi.kt\` 抽成一致的紫色 Design Tokens、字級、卡片、按鈕、Badge、Tab、互動狀態。
- [ ] \`NeonBackdrop.kt\`、\`themes.xml\`、\`drawable/\` 提供全頁一致背景與漸層。
- [ ] 套件設定入口與原生 MaterialDialog/BottomSheet 色票一致；設計可及性與縮放規範。
- [ ] 360dp + 411dp + 大字體煙霧測試，確保舊版功能沒有被底部導覽遮擋。

## P1 四個主入口
- [ ] **主掃描** \`MainActivity.kt\`、\`activity_main.xml\`、\`SignalAdapter.kt\`、\`ResultFilterBar.kt\`、\`item_stock_signal.xml\`：掃描控制、真實進度、結果卡、A–G 快捷卡。
- [ ] **條件與工具** \`showSettings()\`、\`D/E/F/GPatternEditor.kt\`、\`ExclusionEditor.kt\`、\`ExitRulesEditor.kt\`、\`RuleNumberFields.kt\`：策略配置、禁股、限價、匯入、背景診斷。
- [ ] **歷史回測** \`BacktestActivity.kt\`、\`BacktestJournalActivity.kt\`、\`BacktestProfitPanel.kt\`、\`BacktestFiveMinutePanel.kt\`：數據卡、圖表、日誌、交易明細。
- [ ] **自動測試機器人** \`BtRobotActivity.kt\`、\`BtRobotHistoryActivity.kt\`、\`BtRobotRulesDialog.kt\`、\`RobotScanView.kt\`：狀態、規則、任務、歷史記錄與恢復。

## P2 全部次要頁與深層互動
- [ ] **更新／版本日誌** \`AppUpdateActivity.kt\`：版號、GitHub 更新、驗證及下載進度。與 [PR #22](https://github.com/rex0966445518/stock-monitor-updates/pull/22) 衝突檢查，嚴禁移除簽章與版本安全邏輯。
- [ ] **股票詳情／財報／K 線** \`StockChartDialog.kt\`、\`FinancialDialog.kt\`、\`FinancialPanelView.kt\`、\`WantGooInlineChartView.kt\`：真實資料來源與更新時間。
- [ ] **其他完整頁面** \`ScanArchiveActivity.kt\`、\`BacktestJournalActivity.kt\`、\`BtRobotHistoryActivity.kt\`、\`PaperTradingActivity.kt\`、\`PaperStrategyActivity.kt\`、\`StockToolsActivity.kt\`、\`TradeSideVolumeImportActivity.kt\`。
- [ ] **所有深層彈窗與例外狀態**：Dialog、BottomSheet、EditText、Spinner、DatePicker、Loading、Empty、Error、Permission；點進去沒有舊版樣式。

## P3 驗收與發布
- [ ] 截圖差異測試涵蓋首頁／主掃描／A-G／回測／機器人／更新／股票詳情與所有彈窗。
- [ ] Android 裝置矩陣、字級調整、背景掃描、回測續跑、機器人暫停／恢復、設定與日誌保存、CSV、股票跳轉。
- [ ] GitHub \`Build Android APK\` PR review APK 成功且真機驗收。
- [ ] 合併前檢查 [PR #23](https://github.com/rex0966445518/stock-monitor-updates/pull/23) 與 [PR #22](https://github.com/rex0966445518/stock-monitor-updates/pull/22) 對 \`MainActivity\`／\`AppUpdateActivity\` 的交集。
- [ ] 只有 main 正式 workflow 成功、\`latest-android\` APK/manifest 可公開下載並驗證才標示「已可一鍵更新」。

## 技術驗收底線
1. 每次 PR 改版只做 UI／導航連動，保留業務邏輯及原資料模型。
2. 不使用圖片上 2025 日期、示範勝率、價格等作真實數據。
3. G 必須在主掃描、回測及機器人保持可見且可操作。
4. 所有頁面無裁切，點擊後不會出現 Android 舊灰白 Dialog。
5. 統一元件先完成，再逐頁消除自建的舊樣式。
