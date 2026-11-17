# Taiwan Forecast Android 2.6

Package `tw.rex.nextmarket.tracking`, versionCode 16. Based on the published 2.3 native bridge and the 2.4 ice-silver UI.

## Backup coverage

- Index closes: Yahoo → TWSE monthly OHLC → Goodinfo daily OHLC → Statementdog `/taiex` public chart data.
- Foreign and investment-trust net purchases: FinMind → TWSE BFI82U → Goodinfo; amounts normalized to TWD.
- Margin balances: FinMind → Goodinfo, with exact requested dates.
- Market turnover: TWSE → Goodinfo. Statementdog's summary turnover is not substituted because its market scope has not been verified.
- Statementdog exposes closes only in the public chart; OHLC-dependent scores stay missing. No unverified institutional or futures fields are inferred.
- Goodinfo was HTTP 403 during testing; the adapter is implemented but live retrieval was not verified. HTTP/parse/date errors remain visible, never zero-filled. No access-control bypass is used.
- The backup status panel is on-demand. Sources are fetched only when needed or when the user checks connectivity.

The TWSE calendar now requests `date=YYYY0101`; the former queryYear parameter returned the server's current default year, causing an unconfirmed calendar. Return year must match.

Foreign cash scoring no longer depends on Yahoo closes or 21 days of turnover; it needs only same-day net flow and same-day market turnover. All historical samples are bounded by the requested previous trading day. Missing data do not count toward coverage.

## Validation

Statementdog real-page dates and closes, TWSE real calendar and institutional fields, fallback ordering, stale-date rejection, missing OHLC, no execution of provider scripts, and signature equality with 2.3 tested. Android device installation and live Goodinfo access not tested.

Native Java source is in `src`. Resources and manifest compile using AAPT2 with Android API 28. The release uses the unchanged verified 2.3 classes.dex and original certificate; the private signing key must never be committed. APK signature verified using Google's apksig library.

## 2.6 fix
Corrected the official OHLC endpoint to `TAIEX/MI_5MINS_HIST`. Verified 2026-11-17 turnover/20-session ratio and close-position scores using fresh TWSE monthly reports with Yahoo deliberately unavailable. Historical date validation already uses this correct endpoint.
