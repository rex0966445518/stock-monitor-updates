# 台股預測 · 未來科技 UI

已完成新版 WebView 介面與圖示素材。本包不是 APK，尚未發佈至一鍵更新。

## 預覽
開啟 TaiwanForecast-UI-Preview.html。這是離線示意資料，可展開市場訊號、查詢示範歷史卡片與開啟海報。顯示的分數不是實際行情。

## 接回 Android 專案
- 將 assets 內容放入原專案 app/src/main/assets。
- 將 android/res 合併至 app/src/main/res；既有同名圖示請先備份。
- Android 8 以上 application icon 指向 @mipmap/ic_launcher。若最低版本低於 26，另在 mipmap-anydpi/ic_launcher.xml 建立指向 @drawable/ic_forecast 的 bitmap 作 fallback。
- 請保留原 applicationId、原簽章及更新橋接，依現行發佈版本遞增 versionCode。
- 使用原簽章建立 APK 後，先在手機驗證覆蓋安裝與一鍵更新，再更新 GitHub 發佈資訊。

## 設計
曜石黑背景、冰藍按鈕、金屬圖示、雙評分卡片、摺疊訊號、歷史驗證圖卡與頁內導覽。紅漲綠跌，評分與覆蓋率不冒充勝率。支援窄螢幕、鍵盤焦點和減少動態偏好。

## 基準與驗證
以 GitHub taiwanforecast/13/TaiwanForecast.apk 內的資源為基準；保留行情、規則評分、歷史驗證與海報邏輯。修改 index.html 外觀與 update.js 的非 Android 預覽防護。
JavaScript 語法檢查通過；DOM 互動檢查確認六組指標、歷史結果卡片、預覽更新提示正常且無執行錯誤。此環境無法啟動 Chromium，未完成像素排版檢查或 Android 真機測試。尚缺 Android 原始專案與原更新簽章，不能產出可覆蓋安裝的正式 APK。

圖示使用內建影像生成製作：深海軍藍背景、冰藍銀色上升柱與折線箭頭、微量紫色反光、無文字、中央主體並保留裁切空間。

## 觀望提示修正
兩項均為觀望時，標題顯示「開盤、走勢皆為觀望」。未達資料門檻不會被誤標為觀望；勝率計算不變。
