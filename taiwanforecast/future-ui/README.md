# 台股預測 · 冰銀深海藍 UI

本次已替換使用者提供的原始圖示，並更新 WebView 介面與海報配色。**這是介面資源，不是已簽署 APK；正式版仍為 2.3，未變更一鍵更新資訊。**

## 本次設計
- 原圖直接用於首頁品牌及 Android 圖示，沒有重繪。
- 深海藍背景、冰銀漸層操作按鈕、青色光感邊緣及雙評分卡。
- 市場訊號、歷史驗證、資料缺項、對話框、海報採一致配色。
- 保留紅漲綠跌與文字判定，分數和覆蓋率不當作勝率。
- 保留資料不足與觀望的區別；兩項均觀望時顯示「開盤、走勢皆為觀望」。

## 預覽
開啟 TaiwanForecast-UI-Preview.html，所有數值均為示意資料。可操作歷史驗證示例、展開市場指標及生成海報。
修改 assets 後執行 `python scripts/build_preview.py`，可重新產生單檔預覽。

## 接回原 Android 專案
1. 將 assets 合併到 app/src/main/assets。
2. 將 android/res 合併到 app/src/main/res。
3. application 的 android:icon 與 android:roundIcon 都設成 @mipmap/ic_launcher。
4. 保持原 applicationId 與 Poster 原生橋接，遞增 versionCode，使用原簽章建立 APK。
5. 驗證真機覆蓋安裝、抓取行情、歷史查詢、海報儲存及一鍵更新後，再發布。

圖示附 Android 8+ adaptive icon 與舊版 bitmap fallback；adaptive foreground 留有裁切安全空間。資源原圖保持不變。

## 發布限制
目前倉庫只提供正式 APK、WebView 資源及發布既有 APK 的工作流程，沒有原 Android 工程或原簽章；無法由這些檔案直接產出可覆蓋正式版的更新包。

## 本次驗證
JavaScript 語法、Android XML 解析、兩份圖示與上傳原圖逐位元組一致均已通過。此環境的 Chromium 下載失敗，尚未完成瀏覽器視覺檢查或 Android 真機測試。
