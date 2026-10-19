"""Official public market datasets. Values retain their actual source date."""
import csv
import datetime as dt
import io
import json
import math
import re
import urllib.request
from urllib.parse import urlparse

URLS={
 'weekly':'https://opendata.tdcc.com.tw/getOD.ashx?id=1-5',
 'tw_prices':'https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL',
 'otc_prices':'https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes',
 'otc_insti':'https://www.tpex.org.tw/openapi/v1/tpex_3insti_daily_trading',
 'tw_revenue':'https://openapi.twse.com.tw/v1/opendata/t187ap05_L',
 'otc_revenue':'https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O',
 'tw_news':'https://openapi.twse.com.tw/v1/opendata/t187ap04_L',
 'otc_news':'https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap04_O',
 'tw_index':'https://openapi.twse.com.tw/v1/indicesReport/MI_5MINS_HIST',
 'otc_index':'https://www.tpex.org.tw/openapi/v1/tpex_index',
}


def number(value):
    value=str(value).replace(',','').replace('%','').strip()
    result=float(value)
    if not math.isfinite(result):raise ValueError('非有限數值')
    return result


def date(value,month=False):
    digits=re.sub(r'\D','',str(value))
    if month:
        if len(digits)==5:digits=str(int(digits[:3])+1911)+digits[3:]+'01'
        elif len(digits)==6:digits+='01'
    if len(digits)==7:digits=str(int(digits[:3])+1911)+digits[3:]
    return dt.datetime.strptime(digits,'%Y%m%d').date().isoformat()


def code(value):
    value=str(value).strip()
    return value if re.fullmatch(r'\d{4}',value) else None


def fetch(url):
    allowed={'opendata.tdcc.com.tw','openapi.twse.com.tw','www.twse.com.tw','www.tpex.org.tw'}
    if urlparse(url).scheme!='https' or urlparse(url).hostname not in allowed:raise ValueError('資料來源不在官方名單')
    request=urllib.request.Request(url,headers={'User-Agent':'MitakeDetector public-data research/0.3.55','Accept':'application/json,text/csv'})
    with urllib.request.urlopen(request,timeout=10) as response:
        if urlparse(response.geturl()).hostname not in allowed:raise ValueError('來源重新導向至未知網站')
        raw=response.read(12*1024*1024+1)
    if len(raw)>12*1024*1024:raise ValueError('資料超過大小限制')
    text=raw.decode('utf-8-sig')
    if text.lstrip().startswith('<'):raise ValueError('來源回傳網頁，並非資料')
    return text


def weekly(text):
    groups={}
    for row in csv.DictReader(io.StringIO(text)):
        ticker=code(row['證券代號'])
        if not ticker:continue
        stamp=date(row['資料日期']);tier=int(row['持股分級'])
        groups.setdefault((ticker,stamp),{})[tier]=(number(row['人數']),number(row['股數']),number(row['占集保庫存數比例%']))
    output=[]
    for (ticker,stamp),tiers in groups.items():
        if not set(range(1,18))<=set(tiers):continue
        total=tiers[17][1]
        if total<=0:continue
        # 12--15 are >400,000 shares; exclude tier16 difference adjustment and17 total.
        large=sum(tiers[i][1] for i in range(12,16))/total*100
        small=sum(tiers[i][1] for i in range(1,4))/total*100
        output.append(('weekly',ticker,stamp,dict(large=large,small=small,total=total,holders=tiers[17][0])))
    if not output:raise ValueError('集保資料缺少完整持股分級')
    return output


def prices(rows,otc=False):
    result=[]
    for row in rows:
        ticker=code(row.get('SecuritiesCompanyCode' if otc else 'Code'))
        if not ticker:continue
        try:
            out=dict(name=row.get('CompanyName' if otc else 'Name',''),market='TPEx' if otc else 'TWSE')
            mapping={'open':'Open','high':'High','low':'Low','close':'Close','volume':'TradingShares','amount':'TransactionAmount'} if otc else {'open':'OpeningPrice','high':'HighestPrice','low':'LowestPrice','close':'ClosingPrice','volume':'TradeVolume','amount':'TradeValue'}
            out.update({k:number(row[v]) for k,v in mapping.items()})
            if out['close']<=0 or out['low']<=0 or out['high']<out['low']:continue
            result.append(('price',ticker,date(row['Date']),out))
        except (ValueError,KeyError,TypeError):continue
    if not result:raise ValueError('未取得有效收盤行情')
    return result


def institutions(data,otc=False,expected=None):
    output=[]
    if otc:rows=data
    else:
        if data.get('stat')!='OK':return []
        stamp=date(data['date'])
        if expected and stamp!=expected:raise ValueError('法人資料日期與要求不一致')
        rows=[dict(zip(data['fields'],values),Date=stamp) for values in data['data']]
    for row in rows:
        ticker=code(row.get('SecuritiesCompanyCode' if otc else '證券代號'))
        if not ticker:continue
        fields=('Foreign Investors include Mainland Area Investors (Foreign Dealers excluded)-Difference','SecuritiesInvestmentTrustCompanies-Difference','Dealers-Difference') if otc else ('外陸資買賣超股數(不含外資自營商)','投信買賣超股數','自營商買賣超股數')
        try:output.append(('insti',ticker,date(row['Date']),dict(zip(('foreign','trust','dealer'),[number(row[k]) for k in fields]))))
        except (ValueError,KeyError,TypeError):continue
    if otc and not output:raise ValueError('法人欄位無法解析')
    return output


def monthly(data,ticker,market):
    tables=data.get('tables',[]) if market=='TPEx' else [data]
    output=[]
    for table in tables:
        title=str(table.get('title',''))+' '+str(table.get('subtitle',''))
        if any(ch.isdigit() for ch in title) and not re.search(r'(?<![0-9])'+re.escape(ticker)+r'(?![0-9])',title):raise ValueError('歷史行情股票代號未對上')
        for row in table.get('data',[]):
            try:
                stamp=date(row[0]);multiplier=1000 if market=='TPEx' else 1
                volume=number(row[1])*multiplier;amount=number(row[2])*multiplier
                opening,high,low,close=map(number,row[3:7])
                if min(opening,high,low,close)<=0:continue
                output.append(('price',ticker,stamp,dict(market=market,open=opening,high=high,low=low,close=close,volume=volume,amount=amount)))
            except (ValueError,IndexError,TypeError):continue
    if not output:raise ValueError('個股歷史行情尚未取得')
    return output


def revenue(rows):
    output=[]
    for row in rows:
        ticker=code(row.get('公司代號'))
        if not ticker:continue
        try:
            output.append(('revenue',ticker,date(row['資料年月'],month=True),dict(name=row.get('公司名稱',''),industry=row.get('產業別',''),yoy=number(row['營業收入-去年同月增減(%)']),mom=number(row['營業收入-上月比較增減(%)']),notes=row.get('備註',''))))
        except (ValueError,KeyError,TypeError):continue
    if not output:raise ValueError('營收欄位無法解析')
    return output


def notices(rows):
    result=[]
    for row in rows:
        row={k.strip():v for k,v in row.items()};ticker=code(row.get('公司代號'))
        if not ticker:continue
        try:result.append(('news',ticker,date(row['發言日期'])+'T'+str(row.get('發言時間','')).zfill(6),dict(title=row.get('主旨',''),detail=row.get('說明',''))))
        except (KeyError,ValueError):continue
    return result


def indices(rows,otc=False):
    return [('index','TPEx' if otc else 'TWSE',date(r['Date']),dict(close=number(r['Close' if otc else 'ClosingIndex']))) for r in rows]


def otc_institution_history(data,expected):
    if data.get('stat','').lower()!='ok':return []
    output=[]
    for table in data.get('tables',[]):
        if not table.get('data'):continue
        if date(table.get('date',data.get('date','')))!=expected:raise ValueError('上櫃法人日期不一致')
        fields=table.get('fields',[])
        if len(fields)!=24 or fields[:2]!=['代號','名稱'] or fields[-1]!='三大法人買賣超股數合計':raise ValueError('上櫃法人歷史欄位格式改變')
        for row in table.get('data',[]):
            ticker=code(row[0])
            if ticker and len(row)==24:output.append(('insti',ticker,expected,dict(foreign=number(row[4]),trust=number(row[13]),dealer=number(row[22]))))
    return output
