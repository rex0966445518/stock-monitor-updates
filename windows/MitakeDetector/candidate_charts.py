"""Candidate evidence cards. Daily data are cached official observations, never live ticks."""
import datetime as dt
import math
import re
import threading
import time
import webbrowser


def source_links(code):
    if not re.fullmatch(r'[0-9]{4}',code):raise ValueError('股號必須為四位數字')
    from investing_chart import chart_url
    return (chart_url(code),
            f'https://goodinfo.tw/tw/ShowK_Chart.asp?STOCK_ID={code}')


def daily_series(rows):
    result={}
    today=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
    for row in rows:
        try:
            day=dt.date.fromisoformat(row['day'])
            o,h,l,c,v=(float(row[k]) for k in ('open','high','low','close','volume'))
            if day>today or not all(math.isfinite(x) for x in (o,h,l,c,v)):continue
            if not (0<l<=min(o,c)<=max(o,c)<=h and v>=0):continue
            result[day.isoformat()]=dict(day=day.isoformat(),open=o,high=h,low=l,close=c,volume=v,source=row.get('source',''))
        except (KeyError,ValueError,TypeError):continue
    return [result[k] for k in sorted(result)]


def macd(rows):
    # SMA-seeded EMA(12,26), then SMA-seeded 9-period signal. First valid signal at bar 34.
    def ema(values,n):
        out=[None]*len(values)
        if len(values)<n:return out
        out[n-1]=sum(values[:n])/n
        for i in range(n,len(values)):out[i]=out[i-1]+2/(n+1)*(values[i]-out[i-1])
        return out
    closes=[r['close'] for r in rows];fast=ema(closes,12);slow=ema(closes,26)
    dif=[None if b is None else a-b for a,b in zip(fast,slow)]
    signal=[None]*min(25,len(rows))+ema(dif[25:],9)
    return [(d,s,None if d is None or s is None else 2*(d-s)) for d,s in zip(dif,signal)]


def retained_chart_match(match,peak=0):
    if peak<5 and not (match['total']==6 and match['count']>=5):return None
    result=dict(match)
    result['peak']=max(peak,match['count'] if match['total']==6 else 0)
    result['retained']=peak>=5
    return result


def candidate_heading(match):
    current=f"目前 {match['count']}/{match['total']}"
    if match.get('retained'):
        current+=f"｜曾達 {match['peak']}/6・持續保留"
    return current+'｜'+match.get('text',match.get('label',''))


class CandidateCharts:
    PAGE_SIZE=8
    def __init__(self,app,parent):
        self.app=app;self.frame=parent;self.rows=[];self.page=0;self.cache={};self.pending=set();self.cards={};self.key=None
        self.test_mode=False;self.live_rows=[]
        self.web_cache={};self.photos={};self.last_requests={};self.closed=False
        from investing_chart import InvestingCharts
        from MitakeDetector import BASE
        self.web_service=InvestingCharts(BASE,lambda data:app.events.put((None,'wantgoo_chart',data)))
        tk,ttk=app.tk,app.ttk
        bar=ttk.Frame(parent);bar.pack(fill='x')
        self.status=tk.StringVar(value='等待候選股票；曾達 5/6 或 6/6 後持續保留')
        ttk.Label(parent,textvariable=self.status,wraplength=1000).pack(fill='x',padx=8,pady=4)
        ttk.Button(bar,text='上一頁',command=lambda:self.turn(-1)).pack(side='right')
        ttk.Button(bar,text='下一頁',command=lambda:self.turn(1)).pack(side='right')
        self.test_button=ttk.Button(bar,text='測試 5/6・6/6 卡片',command=self.toggle_test)
        self.test_button.pack(side='right',padx=8)
        self.canvas=tk.Canvas(parent,bg='#101827',highlightthickness=0)
        scroll=ttk.Scrollbar(parent,orient='vertical',command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scroll.set);scroll.pack(side='right',fill='y');self.canvas.pack(fill='both',expand=True)
        self.inner=ttk.Frame(self.canvas);self.window=self.canvas.create_window(0,0,window=self.inner,anchor='nw')
        self.canvas.bind('<Configure>',lambda e:self.canvas.itemconfigure(self.window,width=e.width))
        self.inner.bind('<Configure>',lambda e:self.canvas.configure(scrollregion=self.canvas.bbox('all')))
        self.timer=app.root.after(1000,self.tick)
    def update(self,rows):
        self.live_rows=list(rows)
        self.rows=test_candidates() if self.test_mode else self.live_rows
        self.page=min(self.page,max(0,(len(rows)-1)//self.PAGE_SIZE))
        self.render()
    def turn(self,delta):
        self.page=max(0,min(self.page+delta,max(0,(len(self.rows)-1)//self.PAGE_SIZE)));self.canvas.yview_moveto(0);self.render()
    def render(self):
        visible=self.rows[self.page*self.PAGE_SIZE:(self.page+1)*self.PAGE_SIZE]
        key=tuple((q.code,q.name,q.price,m['count'],m['total'],m.get('peak'),m.get('text'),self.test_mode) for q,m in visible)
        self.status.set(('【測試模式：符合數為示範，不是選股結果】 ' if self.test_mode else '')+f'候選 {len(self.rows)} 檔｜第 {self.page+1}/{max(1,(len(self.rows)+7)//8)} 頁｜日線非即時；五分 K 背景載入／點圖放大')
        if key!=self.key:
            self.key=key
            for child in self.inner.winfo_children():child.destroy()
            self.cards={};self.photos={}
            for q,m in visible:self.make_card(q,m)
        for q,m in visible:
            self.request(q.code)
            self.paint(q.code)
    def make_card(self,q,match):
        tk,ttk=self.app.tk,self.app.ttk
        card=ttk.Frame(self.inner,padding=8);card.pack(fill='x',pady=5)
        price_text='未執行掃描' if q.price is None else f'{q.price:.2f}'
        ttk.Label(card,text=('【測試】 ' if self.test_mode else '')+f'{q.name or "股名待補"}  {q.code}  ｜ {candidate_heading(match)} ｜ 掃描價 {price_text}',font=('Microsoft JhengHei',13,'bold')).pack(anchor='w')
        panels=ttk.Frame(card);panels.pack(fill='x')
        links=source_links(q.code);canvases=[]
        for i,title in enumerate(('5 分 K · Investing.com','日 K · 官方快取','日成交量 · 張','日 MACD · 12/26/9')):
            panels.columnconfigure(i,weight=1,uniform='chart')
            panel=ttk.Frame(panels);panel.grid(row=0,column=i,sticky='nsew',padx=3)
            ttk.Label(panel,text=title).pack(anchor='w')
            c=tk.Canvas(panel,height=175,width=180,bg='#101827',highlightthickness=1,highlightbackground='#35455c');c.pack(fill='x',expand=True)
            canvases.append(c)
            c.bind('<Configure>',lambda e,code=q.code:self.paint(code))
            url=links[0 if i==0 else 1]
            ttk.Button(panel,text='Investing.com原站' if i==0 else 'Goodinfo 原站',command=lambda u=url:threading.Thread(target=webbrowser.open,args=(u,),daemon=True).start()).pack(anchor='w')
        ttk.Button(card,text='重試五分 K',command=lambda code=q.code:self.retry_web(code)).pack(anchor='w')
        detail=self.app.tk.StringVar(value='讀取本地日線…')
        ttk.Label(card,textvariable=detail,wraplength=1100).pack(anchor='w')
        self.cards[q.code]=(canvases,detail)
        canvases[0].bind('<Button-1>',lambda e,code=q.code:self.enlarge(code))
    def request(self,code):
        now=time.monotonic()
        if code in self.pending or now-self.cache.get(code,{}).get('read_at',-1000)<60:return
        self.pending.add(code)
        def work():
            try:
                from research_model import ResearchStore
                from MitakeDetector import BASE
                rows=daily_series(ResearchStore(BASE/'research.sqlite3').facts('price',code))
                payload=dict(code=code,rows=rows,error='',read_at=time.monotonic())
            except Exception as exc:payload=dict(code=code,rows=[],error=str(exc),read_at=time.monotonic())
            self.app.events.put((None,'candidate_chart',payload))
        threading.Thread(target=work,daemon=True,name='candidate-daily-cache').start()
    def receive(self,data):
        code=data['code'];self.pending.discard(code);self.cache[code]=data
        # Bound memory across a day with many candidates.
        while len(self.cache)>200:
            oldest=min(self.cache,key=lambda k:self.cache[k]['read_at']);del self.cache[oldest]
        self.paint(code)
    def paint(self,code):
        if code not in self.cards:return
        canvases,detail=self.cards[code];data=self.cache.get(code);rows=data['rows'] if data else []
        for c in canvases:c.delete('all')
        def message(c,text):c.create_text(max(90,c.winfo_width()/2),85,text=text,fill='#b9cadd',width=max(140,c.winfo_width()-20),justify='center')
        self.paint_web(code,canvases[0],message)
        if not rows:
            note='日線快取不足；啟用籌碼研究後等待背景更新' if data else '讀取本地日線…'
            if data and data['error']:note='日線讀取失敗：'+data['error']
            for c in canvases[1:]:message(c,note)
            detail.set(note);return
        recent=rows[-60:];last=rows[-1]['day'];age=(dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()-dt.date.fromisoformat(last)).days
        detail.set(f'日線資料截至 {last}｜{len(rows)} 筆｜未還原權息｜來源：證交所／櫃買官方資料快取；非 Goodinfo 圖片'+('｜資料已超過 7 天' if age>7 else ''))
        def axes(c,lo,hi):
            w=max(180,c.winfo_width());h=175;pad=34
            if hi<=lo:hi=lo+1
            def y(v):return 22+(hi-v)/(hi-lo)*125
            c.create_text(3,10,anchor='w',text=f'{hi:.2f}',fill='#9eafc5')
            c.create_text(3,151,anchor='w',text=f'{lo:.2f}',fill='#9eafc5')
            c.create_text(pad,165,anchor='w',text=recent[0]['day'][5:],fill='#9eafc5')
            c.create_text(w-3,165,anchor='e',text=last[5:],fill='#9eafc5')
            step=(w-pad-6)/len(recent)
            return lambda i:pad+(i+.5)*step,y,max(1,step*.65)
        c=canvases[1];x,y,bw=axes(c,min(r['low'] for r in recent),max(r['high'] for r in recent))
        for i,r in enumerate(recent):
            color='#ff6a78' if r['close']>=r['open'] else '#40d9ab';px=x(i)
            c.create_line(px,y(r['high']),px,y(r['low']),fill=color)
            top,bottom=sorted((y(r['open']),y(r['close'])))
            c.create_rectangle(px-bw/2,top,px+bw/2,max(top+1,bottom),fill=color,outline=color)
        c=canvases[2];x,y,bw=axes(c,0,max(r['volume']/1000 for r in recent))
        for i,r in enumerate(recent):c.create_rectangle(x(i)-bw/2,y(r['volume']/1000),x(i)+bw/2,y(0),fill='#ff6a78' if r['close']>=r['open'] else '#40d9ab',outline='')
        c=canvases[3];values=macd(rows)[-60:]
        if len(rows)<34:message(c,f'MACD 至少需要 34 筆日線\n目前 {len(rows)} 筆');return
        numbers=[v for triple in values for v in triple if v is not None]+[0]
        x,y,bw=axes(c,min(numbers),max(numbers));c.create_line(x(0),y(0),x(len(recent)-1),y(0),fill='#526174')
        for i,(d,s,hist) in enumerate(values):
            if hist is not None:c.create_rectangle(x(i)-bw/2,y(hist),x(i)+bw/2,y(0),fill='#ff6a78' if hist>=0 else '#40d9ab',outline='')
        for index,color in ((0,'#ffd56a'),(1,'#6fbdff')):
            points=[(x(i),y(v[index])) for i,v in enumerate(values) if v[index] is not None]
            if len(points)>1:c.create_line(*[a for p in points for a in p],fill=color,width=2)
        c.create_text(40,10,anchor='w',text='黃 DIF / 藍 DEA / 柱 2×差',fill='#b9cadd')

    def toggle_test(self):
        self.test_mode=not self.test_mode;self.page=0;self.key=None
        self.test_button.configure(text='結束測試／回正式名單' if self.test_mode else '測試 5/6・6/6 卡片')
        self.update(self.live_rows)
        self.canvas.yview_moveto(0)
        if self.test_mode and '--smoke-test' not in __import__('sys').argv:
            codes=[q.code for q,m in self.rows]
            service=getattr(self.app,'research_service',None)
            if service:service.request(codes)
            for code in codes:
                self.last_requests.pop(code,None)
                self.web_service.request(code)
                self.last_requests[code]=time.monotonic()

    def retry_web(self,code):
        self.last_requests[code]=time.monotonic()
        self.web_service.request(code,force=True)

    def tick(self):
        if self.closed:return
        # Load only the current chart page, not hundreds of off-screen candidates.
        if self.frame.winfo_ismapped() and '--smoke-test' not in __import__('sys').argv:
            now=time.monotonic()
            for code in self.cards:
                if now-self.last_requests.get(code,-1000)>=300:
                    self.last_requests[code]=now;self.web_service.request(code)
        self.timer=self.app.root.after(1000,self.tick)
    def receive_wantgoo(self,data):
        code=data['code'];data=dict(data)
        if data.get('loading'):data['loading_since']=time.monotonic()
        self.web_cache[code]=data
        if not data.get('loading'):self.photos.pop(code,None)
        while len(self.web_cache)>100:
            old=next(iter(self.web_cache));self.web_cache.pop(old);self.photos.pop(old,None);self.last_requests.pop(old,None)
        self.paint(code)
    def paint_web(self,code,c,message):
        data=self.web_cache.get(code)
        if not data:message(c,'等待背景載入Investing.com五分 K');return
        if data.get('error'):message(c,data['error']);return
        from pathlib import Path
        from PIL import Image,ImageTk
        try:
            width=max(160,c.winfo_width()-8);key=(width,data['fetched_at'])
            stored=self.photos.get(code)
            if not stored or stored[0]!=key:
                with Image.open(Path(data['directory'])/'thumb.png') as im:
                    image=im.copy();image.thumbnail((width,140))
                stored=(key,ImageTk.PhotoImage(image,master=c));self.photos[code]=stored
            c.create_image(c.winfo_width()/2,3,anchor='n',image=stored[1])
            stamp=data.get('data_time','未知')
            fetched=dt.datetime.fromtimestamp(data['fetched_at'],dt.timezone(dt.timedelta(hours=8))).strftime('%H:%M:%S')
            old='（舊交易日）' if re.match(r'^\d{4}-\d{2}-\d{2}',stamp) and stamp[:10]!=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date().isoformat() else ''
            c.create_text(5,148,anchor='nw',text=f"{data.get('source','Investing.com')}｜五分 K\n{stamp}{old} · 點圖放大",fill='#b9cadd',width=width)
        except (OSError,ValueError,KeyError) as exc:message(c,'圖檔讀取失敗：'+str(exc))
    def enlarge(self,code):
        data=self.web_cache.get(code)
        if not data or data.get('error'):return
        from pathlib import Path
        from PIL import Image,ImageTk
        try:
            with Image.open(Path(data['directory'])/'full.png') as im:image=im.copy()
        except OSError:return
        top=self.app.tk.Toplevel(self.app.root);top.title(f'{code} 五分 K · {data.get("data_time","")}')
        image.thumbnail((min(1200,top.winfo_screenwidth()-100),min(850,top.winfo_screenheight()-120)))
        photo=ImageTk.PhotoImage(image,master=top)
        label=self.app.ttk.Label(top,image=photo);label.image=photo;label.pack()
        self.app.ttk.Label(top,text='來源：'+data.get('source','Investing.com')+'｜五分 K，非即時串流。').pack()
    def close(self):
        self.closed=True
        try:self.app.root.after_cancel(self.timer)
        except Exception:pass
        self.web_service.close()


def test_candidates():
    """UI-only fixtures: scores are simulated, all market charts remain real sources."""
    from types import SimpleNamespace
    return [(SimpleNamespace(code=code,name=name,price=None),dict(count=count,total=6))
            for code,name,count in [('2330','台積電',6),('2317','鴻海',5)]]
