# 盤中做空雷達 v0.3.0
獨立 Android 客戶端，保留盤中雷達、加入 15 項極空掃描條件及 GitHub 更新檢查。預設模擬；正式極空掃描需部署 v0.3.0 backend（原始碼交付 ZIP），不需要富邦 API Key；盤中即時行情另需授權。
更新器只接受 short-radar-vX.Y.Z 或 -test 標籤下同名 APK，核對 SHA256、版本、套件與簽章。Android 要求使用者確認安裝。
固定簽章：在 GitHub Actions secrets 設 SHORT_RADAR_KEYSTORE_B64, SHORT_RADAR_STORE_PASSWORD, SHORT_RADAR_KEY_ALIAS, SHORT_RADAR_KEY_PASSWORD。未設時僅建置 debug 測試 APK；不同簽章無法覆蓋更新，需先備份設定，再進行一次性遷移。私鑰不可放在公開 GitHub 倉庫。
