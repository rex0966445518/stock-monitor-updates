# 三竹盤中轉強偵測器 Windows

Windows 10/11 x64；以使用者目前顯示的排行榜畫面辨識候選訊號。

## 安裝與更新

從 [最新版發布頁](https://github.com/rex0966445518/stock-monitor-updates/releases/tag/mitake-latest) 下載 `MitakeDetector-Setup-v版本.exe`，雙擊安裝。不需要自行安裝 Python 或 OCR 元件。

在偵測器內按「檢查更新／下載安裝」，看到新版後按「下載並安裝新版」。下載完成會核對大小與 SHA-256，再關閉程式、啟動安裝並重新開啟。設定與歷史位於 `%LOCALAPPDATA%\MitakeDetector`，不在程式安裝目錄內。

獨立更新資訊：`https://github.com/rex0966445518/stock-monitor-updates/releases/download/mitake-latest/mitake-update.json`。

## 三竹畫面設定

1. 將可見欄位由左至右排成：股號、股名、成交價、漲幅%、總量（張）、換手率%。股號需獨立欄位。
2. 按「框選排行榜」圈選表格，接著「校正欄位」逐欄點選中心。
3. 設定條件，按「開始實盤」。保持表格可見；移動或縮放後重新校正。
4. 沒有換手率可不勾選該條件，校正時在最後點選右側預留位置。

只偵測可見列，沒有自動翻頁、全市場資料或三竹官方SDK串接。觀測高點為本輪看到的高點；量速比需連續觀測10分鐘。不是漲停保證，不執行交易。需使用者的實際三竹畫面進一步驗證辨識效果。

## 開發與發布

本目錄的來源變更推送到 `main`，會觸發 `.github/workflows/mitake-windows.yml`：

- Windows runner 執行核心與更新驗證測試。
- PyInstaller 編譯 EXE，再執行實際 EXE 的 Tcl/Tk、OCR、資料庫與介面 smoke test。
- Inno Setup 產生免管理員權限的安裝檔，測試安裝、覆蓋更新與保留設定。
- 發布不可變的 `mitake-v版本`，確認下載 SHA-256 後才更新 `mitake-latest` 更新資訊。

發新版先修改 `MitakeDetector.py` 的 VERSION 與 `RELEASE_NOTES.md`，必須使用新版本號。版本發布已存在時，不覆寫該版本安裝檔。

本機開發：`python -m pip install -r requirements.txt`，`python MitakeDetector.py`。
核心測試：`python MitakeDetector.py --self-test`，`python -m unittest -v test_updater`。
