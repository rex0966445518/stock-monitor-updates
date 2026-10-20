# 漲停板探測器-雷允澤製 v0.3.77

- 強化09:00–12:50持續價量觀察：新增上漲採樣區間量占比、回落/上漲量速及30分鐘採樣低點變化。
- 使用累計量差值，排除倒退及跨長缺口的量；不是逐筆主動買量、VWAP或真正五分OHLC。
- 啟用緩漲檢核時，上漲區間量占比預設至少55%，至少3個回落樣本時回落量速不可大於上漲量速；低點變化僅供觀察。
- 盤中明細顯示樣本數、已經過時段覆蓋及量價線索，未到12:50不提前通過。
- 門檻為待回測研究規則，不代表大戶身分、勝率或保證上漲。

研究依據：Fidelity Accumulation Distribution（價量交叉觀察）；TDCC集保股權分散表（週末持股，不能確認盤中買方）。
https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/accumulation-distribution
https://original-www.tdcc.com.tw/portal/zh/smWeb/qryStock
