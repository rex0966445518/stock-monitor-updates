"""Background research network work is independent of OCR and its F1 lifecycle."""
import datetime as dt
import hashlib
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
from research_model import ResearchStore
import research_sources as src


class ResearchService:
    def __init__(self,base,emit,fetcher=src.fetch):
        self.store=ResearchStore(base/'research.sqlite3');self.cache=base/'research-cache';self.cache.mkdir(exist_ok=True)
        self.emit=emit;self.fetcher=fetcher;self.stop=threading.Event();self.condition=threading.Condition()
        self.watch_path=base/'research-watch.json';self.enabled=True;self.watch=[]
        try:self.watch=[c for c in json.loads(self.watch_path.read_text('utf-8')) if src.code(c)][:100]
        except (OSError,ValueError,TypeError):pass
        self.pending={};self.seen={};self.inflight=set();self.fetched_at={};self.force=False;self.last_base=0;self.thread=None
    def request(self,codes,force=False):
        with self.condition:
            codes=list(codes)[:20]
            updated=list(dict.fromkeys(codes+self.watch))[:100]
            if updated!=self.watch:
                self.watch=updated
                try:
                    temporary=self.watch_path.with_suffix('.tmp');temporary.write_text(json.dumps(self.watch),'utf-8');temporary.replace(self.watch_path)
                except OSError as exc:self.emit('research_status','追蹤清單保存失敗：'+str(exc))
            for code in codes:
                if code not in self.inflight and src.code(code) and (force or time.monotonic()-self.seen.get(code,-1e20)>600):self.pending[code]=None
            self.force=self.force or force
            if not self.pending:return
            if not self.thread or not self.thread.is_alive():
                self.thread=threading.Thread(target=self.run,daemon=True);self.thread.start()
            self.condition.notify_all()
    def close(self):
        self.stop.set()
        with self.condition:self.condition.notify_all()
    def text(self,url,force=False):
        path=self.cache/(hashlib.sha256(url.encode()).hexdigest()+'.json')
        if path.exists() and not force:
            try:
                cached=json.loads(path.read_text('utf-8'))
                if time.time()-cached['fetched']<21600:
                    self.fetched_at[url]=cached['fetched'];return cached['text']
            except (OSError,ValueError,KeyError):pass
        text=self.fetcher(url)
        self.fetched_at[url]=time.time()
        temporary=path.with_suffix('.tmp');temporary.write_text(json.dumps(dict(fetched=self.fetched_at[url],text=text),ensure_ascii=False),'utf-8');temporary.replace(path)
        return text
    def dataset(self,key,url,parser,force=False):
        if self.stop.is_set():return []
        try:
            rows=parser(self.text(url,force))
            self.store.put(rows,url)
            self.store.source_status(key,self.fetched_at.get(url,time.time()),f'已取得{len(rows)}筆' if rows else '該期尚無資料')
            self.emit('research_status',f'研究資料：{key}，{len(rows)}筆')
            return rows
        except Exception as exc:
            self.store.source_status(key,time.time(),'更新失敗：'+str(exc))
            self.emit('research_status',f'研究資料 {key} 更新失敗，保留已存來源日期：{exc}')
            return []
    def base_refresh(self,force=False):
        parsers={
          'weekly':src.weekly,'tw_prices':lambda t:src.prices(json.loads(t)),
          'otc_prices':lambda t:src.prices(json.loads(t),True),
          'otc_insti':lambda t:src.institutions(json.loads(t),True),
          'tw_revenue':lambda t:src.revenue(json.loads(t)),'otc_revenue':lambda t:src.revenue(json.loads(t)),
          'tw_news':lambda t:src.notices(json.loads(t)),'otc_news':lambda t:src.notices(json.loads(t)),
          'tw_index':lambda t:src.indices(json.loads(t)),'otc_index':lambda t:src.indices(json.loads(t),True)}
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(self.dataset,k,src.URLS[k],parser,force) for k,parser in parsers.items()]
            for future in as_completed(futures):future.result()
        # T86 has actual historical dates; holidays/unfinished dates remain absent.
        available=0;today=dt.date.today()
        for offset in range(14):
            if self.stop.is_set():break
            day=today-dt.timedelta(days=offset)
            if day.weekday()>=5:continue
            url=f'https://www.twse.com.tw/rwd/zh/fund/T86?date={day:%Y%m%d}&selectType=ALLBUT0999&response=json'
            rows=self.dataset('tw_insti_'+day.isoformat(),url,lambda text,d=day.isoformat():src.institutions(json.loads(text),expected=d),force)
            otc_url=f'https://www.tpex.org.tw/www/zh-tw/insti/dailyTrade?date={day:%Y/%m/%d}&type=Daily&sect=AL&response=json'
            self.dataset('otc_insti_'+day.isoformat(),otc_url,lambda text,d=day.isoformat():src.otc_institution_history(json.loads(text),d),force)
            if rows:available+=1
            if available>=5:break
        self.last_base=time.monotonic()
    def backfill(self,code,force=False):
        facts=self.store.facts('price',code)
        if facts:market=facts[-1].get('market')
        else:
            revenue=self.store.facts('revenue',code)
            if not revenue:return
            market='TPEx' if 'tpex.org.tw' in revenue[-1]['source'] else 'TWSE'
        today=dt.date.today();statuses={k:status for k,_,status in self.store.statuses()}
        for offset in range(18):
            if self.stop.is_set():break
            month_index=today.year*12+today.month-1-offset
            year,month=divmod(month_index,12);month+=1
            if market=='TWSE':url=f'https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date={year:04d}{month:02d}01&stockNo={code}&response=json'
            else:url=f'https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock?code={code}&date={year:04d}/{month:02d}/01&response=json'
            key=f'history_{code}_{year:04d}{month:02d}'
            # Completed historical months are immutable cache hits; failed months retry later.
            if offset>=2 and statuses.get(key,'').startswith('已取得'):continue
            self.emit('research_status',f'{code} 自動補抓18個月日線｜第{offset+1}/18月')
            self.dataset(key,url,lambda text:src.monthly(json.loads(text),code,market),force)
            if self.stop.wait(.2):break
    def weekly_history(self,code):
        from tdcc_history import TDCC,URL
        weeks=self.store.facts('weekly',code)
        if len(weeks)>=3 and (dt.date.today()-dt.date.fromisoformat(weeks[-1]['day'])).days<=7:return
        try:
            for rows,url in TDCC().history(code,{r['day'] for r in weeks},self.stop):self.store.put(rows,url)
            self.store.source_status('weekly_history_'+code,time.time(),'歷史三期已查詢')
        except Exception as exc:self.emit('research_status',f'{code} 集保歷史更新失敗，保留已存資料：{exc}')
    def validate(self):
        from research_validation import Validator
        histories={c:self.store.facts('price',c) for c in self.watch}
        state=Validator(self.store.path).run(histories)
        self.emit('research_status','自動驗證：'+state['status'])
    def run(self):
        while not self.stop.is_set():
            with self.condition:
                if not self.pending:
                    self.condition.wait(10)
                    if not self.pending:
                        if self.enabled and self.watch and time.monotonic()-self.last_base>21600:
                            self.pending.update(dict.fromkeys(self.watch))
                        else:continue
                codes=list(self.pending)[:30]
                for code in codes:self.pending.pop(code,None)
                self.inflight.update(codes)
                force=self.force;self.force=False
            try:
                self.emit('research_results',{code:self.store.report(code) for code in codes})
                if force or time.monotonic()-self.last_base>21600:self.base_refresh(force)
                for index,code in enumerate(codes):
                    if self.stop.is_set():return
                    self.emit('research_status',f'籌碼研究 {index+1}/{len(codes)}｜{code} 歷史日線')
                    self.backfill(code,force)
                    if self.stop.is_set():return
                    self.weekly_history(code)
                    self.seen[code]=time.monotonic()
                    self.emit('research_results',{code:self.store.report(code)})
                if not self.stop.is_set():
                    self.validate()
                    self.emit('research_results',{code:self.store.report(code) for code in codes})
            except Exception as exc:self.emit('research_status','籌碼研究暫停，可重試：'+str(exc))
            finally:
                with self.condition:self.inflight.difference_update(codes)
        

