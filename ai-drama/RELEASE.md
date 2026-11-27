# 固定發布規則
- 每次功能修改、修正後提升 app/build.gradle versionName 與 versionCode，並同步 index.html 的 VERSION 與畫面版本。
- ai-drama-app 分支提交自動執行 APK 編譯、簽章驗證與 GitHub Release。
- 未設定固定簽章時，只發布 ai-drama-test-v* 測試預發布（不會被 App 內更新讀取）。禁止以不固定的 debug 簽章冒充正式更新。
- 正式版必須在 GitHub Actions repository secrets 設定 DRAMA_KEYSTORE_B64、DRAMA_KEYSTORE_PASSWORD、DRAMA_KEY_ALIAS、DRAMA_KEY_PASSWORD。請保管原始金鑰，禁止把金鑰放到公開 repo。正式發布自動比對上一版簽章與 versionCode，失敗禁止發布。
- 正式標籤 ai-drama-v*；資產 AI-Drama-Search-release.apk。設定好後 App 可檢查正式版本並下載，Android 仍要求確認安裝。已有 debug 版可能須另外遷移觀看紀錄，不保證可覆蓋。
- 使用 --latest=false，避免影響同庫股票 App。
