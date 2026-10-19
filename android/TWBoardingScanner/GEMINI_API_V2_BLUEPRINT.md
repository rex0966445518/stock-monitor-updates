# Gemini AI 籌碼分析 v2 — 系統設計 / 尚未上線

本文件是 v0.5.0 **下一階段設計**，不是已啟用的 Gemini API 服務；v0.5.0 的 APK 只在使用者每次授權螢幕擷取後，巡覽籌碼K線最多七頁並保留圖片，需手動分享。

## 目的
使用者輸入台股股號，AI-離職神器依序擷取／核對七類資訊：
1. 日K、2. 籌碼日報、3. 法人、4. 多空、5. 主力、6. 大戶、7. 財報；
由安全後端提交 Google Gemini API，將圖片證據、實際欄位日期、缺頁情況一併輸入，回傳 JSON 給 App 產生分析卡。

## 系統邊界

```text
Android App
  ├─ ABCDEF 筛选结果／單一股號
  ├─ Android MediaProjection 畫面授權（每工作階段 1 次）
  ├─ 籌碼K線可見頁面導覽 → JPG + manifest.json
  └─ 使用者明確同意送出 → HTTPS 認證 → 雲端後端
                                         ├─ 驗證上傳權限、7 張圖與大小限制
                                         ├─ 依股號綁定七種頁面、日期、驗證狀態
                                         ├─ 傳送 Gemini generateContent (多個 image/jpeg parts)
                                         ├─ 檢查結構化 JSON 與證據、避免資料幻想
                                         └─ 回傳分析卡 / 儲存歷史 / 配額控制
```

**不要**在 APK、GitHub、程式碼日誌或 Android SharedPreferences 中寫入正式 GEMINI_API_KEY。
後端建議使用 Google Cloud Run + Secret Manager，透過最小權限服務帳號與認證 token 存取；或其他等價安全後端。

## API 建議

### POST /v1/chip-analysis （需認證、需同意）

客戶端以 multipart/form-data 或預簽上傳連結提交：
- `stock_code`: 四至六位數字字串（例如 2330）。
- `capture_id`: 單次任務識別碼，避免重複處理。
- `manifest`: 錄入七頁 `key, status, verification, captured_at, source_app` 的清單。
- `images`: 存在且使用者確認可提交的頁面圖片，最多 7 張，不接受其他類型。
- `consent`: 使用者在當次分析時明確允許傳送截圖。
- `source_rights_confirmed`: 確認帳號及提供方授權條款允許所需用途。

後端校驗：MIME、檔案大小、圖片尺寸、圖片實際股號、缺圖、重複圖；若來源頁籤僅 `changed_unverified`，標註「未確認」並在模型結果中降低信心，不能當成可靠證據。

回應範例（JSON schema，可使用 responseMimeType='application/json' 加 responseSchema）：
```json
{
  "schema_version": "1.0",
  "stock_code": "2330",
  "capture_id": "2330_YYYYMMDDhhmmss",
  "status": "completed",
  "data_as_of": null,
  "page_evidence": [
    {
      "page": "kline",
      "present": true,
      "verified": false,
      "source_date": null,
      "facts": [],
      "limitations": ["需核對圖中股號、日期及頁籤"]
    }
  ],
  "whale_behavior": "insufficient_data",
  "whale_evidence": [],
  "main_force_behavior": "unknown",
  "institutional_flow": "unknown",
  "setups": {
    "A": "insufficient_data",
    "B": "insufficient_data",
    "C": "insufficient_data",
    "D": "insufficient_data",
    "E": "insufficient_data",
    "F": "insufficient_data"
  },
  "entry_watch": [],
  "support_levels": [],
  "resistance_levels": [],
  "invalidations": [],
  "risks": ["缺少可查證的價量、日期或籌碼數據"],
  "research_score": null,
  "score_is_win_probability": false,
  "human_review_required": true
}
```

### Gemini 呼叫方式

Google Gemini REST API：`POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`

- 在 `contents.parts` 放入繁體中文研究指令、七頁標籤文字及最多 7 個 `inlineData` 圖片 `{mimeType:'image/jpeg',data:'<base64>'}`。
- 在 `generationConfig` 設 `responseMimeType: 'application/json'` 與可驗證的 `responseSchema`。
- 使用目前有效且成本合適的 Gemini 多模態模型名稱，部署時以環境變數 `GEMINI_MODEL` 指定，不在此文件硬編型號。
- 錯誤代碼：401/403（授權）、413（圖片過大）、429（配額）、502（Gemini 暫時失敗）、422（無法辨識股號或檔案）。
- 設定等候、退避重試、工作進度、可取消、同一 capture_id 冪等；重試不需重新截圖，也不可重複計費而未告知。

## 分析防誤判
- Gemini 會錯讀小字、成交張數、主力買賣超等數據。AI 敘述需綁定原始頁面索引、證據欄位，沒看到則 `null`，不用猜值。
- 不能把「大戶增加」直接推論成「必然拉抬」，或把研究用 0–100 分當作真實勝率。
- 不自行下單；符合 ABCDEF 的訊號、歷史回測勝率與 Gemini 判讀獨立顯示。
- 指定截圖保留期限，允許使用者手動刪除本機/後端影像。
- 如供應商訂閱或授權禁止自動抓圖、再利用或大量抓取，須改走獲授權 API 或縮減功能，不能繞過會員/螢幕保護。

## 交付順序
1. v0.5.0 真機驗收 7 頁搜尋/切換/畫面對應，不同 CMoney 會員畫面需要按真機截圖逐項校準。
2. v0.5.1 建立已授權圖片上傳、傳輸確認、重試與錯誤回報。
3. v0.5.2 Cloud Run Gemini 分析後端及 JSON 校驗，出示七頁證據對照的分析卡。
4. v0.5.3 ABCDEF 批次佇列、上限控制、快取、評分歷史與回測對照。

## 官方技術文件
- Google Gemini generateContent: https://ai.google.dev/api/generate-content
- Android MediaProjection: https://developer.android.com/media/grow/media-projection
