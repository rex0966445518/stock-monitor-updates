# -*- coding: utf-8 -*-
"""漲停板探測器-雷允澤製 — Windows 桌面觀察工具。
啟動：python MitakeDetector.py；核心驗證：python MitakeDetector.py --self-test
開啟三竹熱門排行後按F8自動設定並開始掃描。
本程式採可見畫面 OCR，沒有三竹 SDK 登入或官方行情串接。
"""
import csv, dataclasses, datetime as dt, importlib.util, json, math, os
import queue, random, re, sqlite3, subprocess, sys, threading, time, unicodedata
from collections import deque
from auto_ui import AutoUI
from pathlib import Path

VERSION = '0.3.46'
FIELDS = ['code', 'name', 'price', 'gain', 'volume', 'turnover']
LABELS = ['股號', '股名', '成交價', '漲幅%', '總量（張）', '換手率%']
BASE = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'MitakeDetector'
DEFAULT = dict(interval=5, min_price=50, min_volume=1000, min_gain=1,
               max_gain=8.5, min_turnover=0, use_turnover=False,
               use_accel=False, accel=1.5, use_breakout=True, cooldown=300,
               centers=[6, 22, 40, 57, 75, 92], region=None, auto_config=None, column_bounds=None, voice_enabled=True)

@dataclasses.dataclass
class Quote:
    code: str
    name: str
    price: float
    gain: float
    volume: float
    turnover: object = None
    ts: float = 0


def number(value):
    value = unicodedata.normalize('NFKC', str(value)).strip().replace(',', '').replace('%', '').replace(' ', '')
    value = value.replace('−', '-').replace('＋', '+')
    if not re.fullmatch(r'[+-]?\d+(?:\.\d+)?', value):
        raise ValueError('數字格式不明')
    n = float(value)
    if not math.isfinite(n):
        raise ValueError('數值無效')
    return n


def parse_ocr(result, width, centers, ts):
    """以股號的 y 座標作列錨點，避免缺欄時數字移位。"""
    cells = []
    for box, raw, confidence in result or []:
        if confidence < .70:
            continue
        x = sum(p[0] for p in box) / 4 / width * 100
        y = sum(p[1] for p in box) / 4
        h = max(p[1] for p in box) - min(p[1] for p in box)
        col = min(range(6), key=lambda k: abs(x - centers[k]))
        cells.append((col, y, max(h, 8), str(raw)))
    out, rejected = [], 0
    anchors = [c for c in cells if c[0] == 0 and re.fullmatch(r'\d{4,6}', unicodedata.normalize('NFKC', c[3]).strip())]
    seen = set()
    for _, y, h, rawcode in anchors:
        code = unicodedata.normalize('NFKC', rawcode).strip()
        if code in seen:
            rejected += 1
            continue
        seen.add(code)
        row = {}
        ambiguous = False
        for col in range(1, 6):
            near = [c for c in cells if c[0] == col and abs(c[1] - y) < h * .55]
            if len(near) > 1:
                ambiguous = True
            elif near:
                row[FIELDS[col]] = near[0][3]
        try:
            if ambiguous:
                raise ValueError('列欄位重疊')
            price, gain, vol = (number(row[k]) for k in ['price', 'gain', 'volume'])
            turn = number(row['turnover']) if 'turnover' in row else None
            if not (0 < price < 1000000 and -30 <= gain <= 100 and vol >= 0 and vol.is_integer()):
                raise ValueError('超出合理值')
            if turn is not None and not 0 <= turn <= 1000:
                raise ValueError('換手率異常')
            out.append(Quote(code, row.get('name', ''), price, gain, vol, turn, ts))
        except (ValueError, KeyError):
            rejected += 1
    return out, rejected


class Engine:
    def __init__(self):
        self.history = {}
        self.last_alert = {}
        self.highs = {}
        self.pending = {}
        self.last_change = {}
        self.session_day = None

    def process(self, quotes, config):
        rows, alerts = [], []
        for q in quotes:
            day = dt.datetime.fromtimestamp(q.ts).date()
            if day != self.session_day:
                self.__init__()
                self.session_day = day
            hist = self.history.setdefault(q.code, deque())
            previous = hist[-1] if hist else None
            if previous and q.ts <= previous.ts:
                continue
            # 跳動過大或量倒退時，不寫進基準，等待下一個正常讀值。
            if previous and (q.volume < previous.volume or abs(q.price / previous.price - 1) > .03):
                rows.append((q, None, '讀值跳動／量倒退，已略過', False))
                continue
            if not previous or (q.price, q.volume, q.gain, q.turnover) != (previous.price, previous.volume, previous.gain, previous.turnover):
                self.last_change[q.code] = q.ts
            stale = q.ts - self.last_change[q.code] >= 60
            prior_high = self.highs.get(q.code, q.price)
            pending = self.pending.get(q.code)
            breakout = False
            if pending:
                level, count = pending
                if q.price >= level:
                    count += 1
                    breakout = count >= 2
                    self.pending[q.code] = (level, count)
                else:
                    self.pending.pop(q.code, None)
            if previous and q.price > prior_high:
                self.pending[q.code] = (q.price, 1)
                breakout = False
            self.highs[q.code] = max(prior_high, q.price)
            hist.append(q)
            while hist and q.ts - hist[0].ts > 660:
                hist.popleft()
            # 兩個連續 5 分鐘觀測窗；不冒充過去20日同時段量比。
            ratio = None
            if hist and q.ts - hist[0].ts >= 600:
                a = min(hist, key=lambda p: abs(p.ts - (q.ts - 600)))
                b = min(hist, key=lambda p: abs(p.ts - (q.ts - 300)))
                if abs(a.ts - (q.ts - 600)) <= 15 and abs(b.ts - (q.ts - 300)) <= 15 and a.ts < b.ts < q.ts:
                    oldrate = (b.volume - a.volume) / (b.ts - a.ts)
                    newrate = (q.volume - b.volume) / (q.ts - b.ts)
                    if oldrate > 0:
                        ratio = newrate / oldrate
            reasons = []
            if q.price < config['min_price']: reasons.append('股價不足')
            if q.volume < config['min_volume']: reasons.append('總量不足')
            if not config['min_gain'] <= q.gain <= config['max_gain']: reasons.append('漲幅不符')
            if config['use_turnover'] and (q.turnover is None or q.turnover < config['min_turnover']): reasons.append('換手率不足／缺資料')
            if config['use_accel'] and (ratio is None or ratio < config['accel']): reasons.append('量速待累積／不足')
            if config['use_breakout'] and not breakout: reasons.append('等待觀測突破守穩')
            if not previous: reasons.append('等待第二次讀值')
            if stale: reasons.append('60秒讀值未變，請檢查畫面')
            hit = not reasons
            reason = ('轉強候選｜漲幅'+f'{q.gain:+.2f}%'+('｜觀測突破兩次守穩' if config['use_breakout'] else '')+('｜量速'+f'{ratio:.2f}倍' if config['use_accel'] and ratio is not None else '')) if hit else '；'.join(reasons)
            if hit and q.ts - self.last_alert.get(q.code, -1e20) >= config['cooldown']:
                self.last_alert[q.code] = q.ts
                alerts.append((q, reason))
            rows.append((q, ratio, reason, hit))
        return rows, alerts


HELP = '''漲停板探測器-雷允澤製 v0.3.31

【開始觀察】
開啟三竹「熱門排行」，保持視窗完整可見，按F8或「開始掃描」。
首次會自動定位左上類目選單、表格及六個欄位，再開始持續輪切。
再次按F8停止。切離目標視窗、遮住操作區或縮放視窗會停止。
左上類目選單與股票表格分別操作捲動，不需手動切換類目。

【掃描進度】
三竹上緣橫幅持續顯示目前任務、輪次、類目、讀取段數、股數與經過時間。
清單總長未知時不顯示虛構完成百分比，進度條表示當輪類目進度。
預設逐段重疊捲動；連續三次相同股號列推定末端，不等於官方完整市場資料。
辨識空白額外複查兩次；無重疊或超過2000段保護上限會中止。
清單可能持續重新排序，會依股號去重；無法保證捕捉每一筆瞬時變動。
「掃描設定」可關閉逐段讀取，改為指定頁數以縮短每輪觀察時間。

【觀察重點】
目的是持續觀察轉強候選，不是保證預測漲停。
六個讀取欄位：股號、股名、成交價、漲幅%、總量（張）、換手率%。
預設股價>=50、總量>=1000張、漲幅1~8.5%，觀測突破後兩次讀值守穩。
「觀測突破」僅依啟動後看到的最高價，不是完整當日高點。
可選換手率門檻及5分鐘量速比；量速比需觀察10分鐘。
量速比為近5分鐘每秒增量除以前5分鐘增量，不是預估量增或歷史同時段量比。
各排行分開累積觀測基準，排行名稱本身不當作買力或大單數值。
候選股置頂並顯示來源、時間及原因。跨類目結果保留最新讀值；相差60秒的舊結果改標等待複查。
OCR讀值跳動>3%、量倒退、缺欄及連續60秒未變不提醒。
尚未驗證實盤漲停命中率，不自動下單。

【操作與更新】
主頁保留開始、停止、掃描設定與一鍵更新；校正、圖片測試、示範與資料資料夾在進階工具。
辨識元件及模型已內建，不需要另外下載。
更新從本產品GitHub管道下載，核對版本、大小、SHA-256及EXE檔頭後自動安裝重開。
保留本機設定與SQLite觀測紀錄。「匯出本輪CSV」可匯出觀測紀錄。
OCR在本機執行，不傳送三竹畫面或帳密。
實際三竹版本與顯示縮放需現場核對；可先在掃描設定測試輪切。
'''


def format_feedback(text):
    text=str(text)
    if any(word in text for word in ('掃描已暫停','自動設定未完成','錯誤','Traceback')):
        return text.replace('｜','\n').replace('；','；\n')
    return text


class App(AutoUI):
    def __init__(self, root):
        import tkinter as tk
        from tkinter import ttk
        self.tk, self.ttk, self.root = tk, ttk, root
        BASE.mkdir(parents=True, exist_ok=True)
        self.config = dict(DEFAULT)
        try:
            saved = json.loads((BASE / 'settings.json').read_text('utf-8'))
            self.config.update({k: v for k, v in saved.items() if k in DEFAULT})
        except (OSError, ValueError): pass
        self.events, self.stop_event = queue.Queue(), threading.Event()
        self.worker_thread = None
        self.generation = 0
        self.engine = Engine()
        self.records = []
        self.mode = '待機'
        self.auto_active = False
        self.auto_engines = {}
        self.rank_rows = {}
        from price_history import PriceHistory
        self.price_history=PriceHistory()
        self.ocr = None
        self.update_busy = False
        self.update_status = tk.StringVar(value='')
        root.title('漲停板探測器-雷允澤製 v' + VERSION)
        root.geometry(f'{min(1540,root.winfo_screenwidth()-40)}x{min(1000,root.winfo_screenheight()-70)}')
        root.minsize(min(1100,root.winfo_screenwidth()-40),min(800,root.winfo_screenheight()-70))
        root.configure(bg='#0b1020')
        style = ttk.Style()
        style.theme_use('clam')
        style.configure('.', background='#111a2e', foreground='#e8eefb', font=('Microsoft JhengHei', 10))
        style.configure('TButton', padding=8)
        style.configure('TEntry',fieldbackground='#f4f7fc',foreground='#101827',insertcolor='#101827',selectbackground='#245cbb',selectforeground='#ffffff',padding=5)
        style.map('TEntry',fieldbackground=[('disabled','#d9e1ed'),('readonly','#e6edf7')],foreground=[('disabled','#53637a'),('readonly','#101827')])
        style.configure('TCombobox',fieldbackground='#f4f7fc',foreground='#101827',selectbackground='#245cbb',selectforeground='#ffffff')
        style.map('TCombobox',fieldbackground=[('readonly','#e6edf7')],foreground=[('readonly','#101827')])
        style.configure('Treeview', background='#101a2c', fieldbackground='#101a2c', foreground='#e4eaf5', rowheight=30)
        style.map('Treeview', background=[('selected', '#7b253c')])
        tk.Label(root, text='漲停板探測器-雷允澤製', fg='#ff5879', bg='#0b1020', font=('Microsoft JhengHei', 22, 'bold')).pack(anchor='w', padx=20, pady=(15, 3))
        tk.Label(root, text='觀察轉強候選股 · 持續複查量價與突破守穩 · 訊號不代表一定漲停', fg='#9caeca', bg='#0b1020').pack(anchor='w', padx=20)
        bar = ttk.Frame(root); bar.pack(fill='x', padx=15, pady=12)
        for label, action in [('開始／繼續（F8）', self.begin_observation), ('暫停（F8）', self.stop), ('重新開始', self.restart_observation), ('掃描設定', self.configure_auto), ('一鍵更新', self.check_update)]:
            ttk.Button(bar, text=label, command=action).pack(side='left', padx=4)
        advanced = ttk.Menubutton(bar, text='進階工具 ▾')
        menu = tk.Menu(advanced, tearoff=False)
        for label, action in [('重新自動定位', self.auto_setup), ('清除股名記憶', self.clear_stock_names), ('手動框選排行榜', self.select_region), ('手動校正欄位', self.calibrate), ('圖片辨識測試', self.image_test), ('合成資料示範', self.demo), ('辨識元件檢查', self.install), ('資料資料夾', self.open_folder), ('OCR學習紀錄', self.show_ocr_learning), ('中斷診斷', self.show_scan_diagnostics), ('版本日誌', self.show_version_history), ('使用說明', self.help)]:
            menu.add_command(label=label, command=action)
        advanced.configure(menu=menu); advanced.pack(side='left', padx=4)
        self.main_actions=bar
        auto_bar = ttk.Frame(root); auto_bar.pack(fill='x', padx=18, pady=(0, 8))
        self.voice_var=tk.BooleanVar(value=self.config.get('voice_enabled',True))
        ttk.Checkbutton(auto_bar,text='語音提醒',variable=self.voice_var,command=self.toggle_task_voice).pack(side='left',padx=5)
        ttk.Label(auto_bar, text='F8 開始／停止 · 輪切會使用滑鼠 · 離開三竹視窗自動停止').pack(side='left', padx=8)
        opts = ttk.LabelFrame(root, text='篩選條件（變更後，停止並重新開始以套用）'); opts.pack(fill='x', padx=18)
        self.vars = {}
        fields = [('min_price', '股價 ≥'), ('min_volume', '總量張 ≥'), ('min_gain', '漲幅 ≥%'), ('max_gain', '漲幅 ≤%'), ('interval', '間隔秒'), ('cooldown', '提醒間隔秒')]
        for idx, (key, label) in enumerate(fields):
            ttk.Label(opts, text=label).grid(row=0, column=idx*2, padx=4, pady=9)
            self.vars[key] = tk.StringVar(value=str(self.config[key]))
            ttk.Entry(opts, textvariable=self.vars[key], width=7).grid(row=0, column=idx*2+1)
        for idx, (key, label, valkey) in enumerate([('use_turnover', '換手率 ≥%', 'min_turnover'), ('use_accel', '近5分量速比 ≥', 'accel'), ('use_breakout', '觀測突破後連續兩次守穩', None)]):
            self.vars[key] = tk.BooleanVar(value=self.config[key])
            ttk.Checkbutton(opts, text=label, variable=self.vars[key]).grid(row=1, column=idx*4, columnspan=3, padx=5, pady=8, sticky='w')
            if valkey:
                self.vars[valkey] = tk.StringVar(value=str(self.config[valkey]))
                ttk.Entry(opts, textvariable=self.vars[valkey], width=7).grid(row=1, column=idx*4+3)
        self.scan_phase=tk.StringVar(value='待機｜按 F8 開始連續掃描')
        self.phase_label=tk.Label(root,textvariable=self.scan_phase,bg='#12243a',fg='#68f5d1',font=('Microsoft JhengHei',14,'bold'),anchor='w',justify='left')
        self.phase_label.pack(fill='x',padx=20,pady=(7,0))
        self.phase_label.bind('<Configure>',lambda event:self.phase_label.configure(wraplength=max(80,event.width-8)))
        self.status = tk.StringVar(value='開啟三竹「熱門排行」，按 F8 或「開始掃描」。首次會自動定位；再次按 F8 停止。')
        self.status_label=tk.Label(root,textvariable=self.status,bg='#0b1020',fg='#58ddd0',anchor='w',justify='left',wraplength=1120)
        self.status_label.pack(fill='x',padx=20,pady=10)
        self.status_label.bind('<Configure>',lambda event:self.status_label.configure(wraplength=max(80,event.width-8)))
        from scan_rate import ScanRate
        self.scan_rate=ScanRate();self.scan_rate_var=tk.StringVar(value=self.scan_rate.text())
        tk.Label(root,textvariable=self.scan_rate_var,bg='#0b1020',fg='#68f5d1',anchor='w').pack(fill='x',padx=20)
        self.observation_summary=tk.StringVar(value='尚未讀取股票資料｜轉強候選待判斷')
        self.summary_label=tk.Label(root,textvariable=self.observation_summary,bg='#0b1020',fg='#edf2ff',anchor='w',justify='left',wraplength=1400)
        self.summary_label.pack(fill='x',padx=20,pady=(0,6))
        self.summary_label.bind('<Configure>',lambda event:self.summary_label.configure(wraplength=max(80,event.width-8)))
        filters=ttk.Frame(root);filters.pack(fill='x',padx=20,pady=(0,5))
        ttk.Label(filters,text='隱藏結果：').pack(side='left')
        self.result_filters={}
        saved_filters=self.config.get('result_filters',{})
        for key,label in [('gain','漲幅不符'),('turnover','換手率不足（已啟用條件時）'),('price','股價不足')]:
            var=tk.BooleanVar(value=bool(saved_filters.get(key,False)))
            self.result_filters[key]=var
            ttk.Checkbutton(filters,text=label,variable=var,command=self.change_result_filters).pack(side='left',padx=5)
        table_frame = ttk.Frame(root); table_frame.pack(fill='both', expand=True, padx=18)
        cols = ['code','name','match','price','gain','volume','turnover','ratio','state']+[f'history{i}' for i in range(8)]
        self.tree = ttk.Treeview(table_frame, columns=cols, show='headings')
        for key, label, width in zip(cols, LABELS[:2] + ['符合條件'] + LABELS[2:] + ['5分量速比', '觀察原因／尚缺條件'], [75,100,125,90,75,100,85,100,380]):
            self.tree.heading(key, text=label); self.tree.column(key, width=width, stretch=key == 'state')
        for i in range(8):
            self.tree.heading(f'history{i}',text=f'待掃描 {i+1}')
            self.tree.column(f'history{i}',width=100,anchor='e',stretch=False)
        self.tree.configure(displaycolumns=cols[:4]+cols[9:]+cols[4:9])
        self.tree.tag_configure('hit', foreground='#fff1a8',background='#703026',font=('Microsoft JhengHei',10,'bold'))
        self.tree.tag_configure('near',foreground='#ffd36a',background='#222b3d')
        sb = ttk.Scrollbar(table_frame, orient='vertical', command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side='left', fill='both', expand=True); sb.pack(side='right', fill='y')
        horizontal=ttk.Scrollbar(table_frame,orient='horizontal',command=self.tree.xview)
        self.tree.configure(xscrollcommand=horizontal.set)
        horizontal.pack(side='bottom',fill='x',before=self.tree)
        log_frame=ttk.Frame(root);log_frame.pack(fill='x',padx=18,pady=8)
        self.log=tk.Text(log_frame,height=9,wrap='word',bg='#0a1322',fg='#a6b8d2',font=('Microsoft JhengHei',10),state='disabled')
        log_scroll=ttk.Scrollbar(log_frame,orient='vertical',command=self.log.yview)
        self.log.configure(yscrollcommand=log_scroll.set)
        self.log.pack(side='left',fill='x',expand=True);log_scroll.pack(side='right',fill='y')
        foot = ttk.Frame(root); foot.pack(fill='x', padx=18, pady=(0,12))
        # Reserve footer and diagnostic space before the expanding quote table.
        foot.pack_configure(side='bottom',before=table_frame)
        log_frame.pack_configure(side='bottom',before=table_frame)
        ttk.Button(foot, text='匯出本輪 CSV', command=self.export).pack(side='left')
        self.update_bar=ttk.Progressbar(foot,mode='determinate',maximum=100,length=150)
        ttk.Label(foot, textvariable=self.update_status).pack(side='left',padx=8)
        ttk.Label(foot, text='依畫面逐段觀察 · 保持三竹可見 · 不會自動下單').pack(side='right')
        from display_scale import DisplayScale,SCALES
        zoom=ttk.Frame(foot);zoom.pack(side='left',padx=12)
        ttk.Label(zoom,text='介面大小').pack(side='left')
        self.display_scale_var=tk.StringVar(value='150%')
        self.display_scale_box=ttk.Combobox(zoom,textvariable=self.display_scale_var,values=[f'{n}%' for n in SCALES],state='readonly',width=6)
        self.display_scale_box.pack(side='left',padx=4)
        self.display_scale=DisplayScale(self,BASE/'display-settings.json')
        self.display_scale_box.bind('<<ComboboxSelected>>',lambda event:self.display_scale.apply(self.display_scale_var.get()))
        self.display_scale.apply(self.display_scale.initial,save=False)
        from version_history import record_launch
        try:record_launch(BASE,VERSION)
        except sqlite3.Error as exc:self.say('版本啟用紀錄寫入失敗：'+str(exc))
        root.protocol('WM_DELETE_WINDOW', self.close)
        self.init_f8()
        root.after(150, self.poll)

    def restart_observation(self):
        if not self.idle():return
        try:
            from resume_scan import ScanCheckpoint
            (BASE/'scan-checkpoint.json').unlink(missing_ok=True)
            self.scan_checkpoint=None
        except OSError as exc:self.say('無法清除進度：'+str(exc));return
        self.auto_engines={};self.rank_rows={};self.records=[]
        self.begin_observation()

    def save_scan_snapshot(self):
        store=getattr(self,'scan_checkpoint',None)
        if not store or not getattr(self,'scan_resume_active',False):return
        from resume_state import snapshot
        try:store.snapshot(snapshot(self))
        except (ValueError,OSError) as exc:self.say('進度資料保存失敗：'+str(exc))

    def restore_scan_snapshot(self,data):
        if not data:return
        from resume_state import restore
        try:restore(self,data)
        except (ValueError,TypeError,KeyError) as exc:self.say('觀察歷史還原失敗，保留斷點：'+str(exc))

    def begin_observation(self):
        if (self.config.get('auto_config') or {}).get('discovered'):
            self.start_auto()
        else:self.auto_setup(start_after=True)

    def say(self, text):
        text=format_feedback(text)
        self.log.configure(state='normal'); self.log.insert('end', dt.datetime.now().strftime('%H:%M:%S ') + text + '\n')
        self.log.see('end'); self.log.configure(state='disabled')

    def save(self):
        temp = BASE / 'settings.tmp'
        temp.write_text(json.dumps(self.config, ensure_ascii=False, indent=2), 'utf-8')
        temp.replace(BASE / 'settings.json')

    def settings(self):
        from tkinter import messagebox
        try:
            cfg = dict(self.config)
            for key, var in self.vars.items():
                cfg[key] = bool(var.get()) if key.startswith('use_') else float(var.get())
            if not all(math.isfinite(v) and v >= 0 for v in cfg.values() if isinstance(v, (float, int))): raise ValueError()
            if cfg['interval'] < 2 or cfg['cooldown'] < 10 or cfg['min_gain'] > cfg['max_gain']: raise ValueError()
            self.config = cfg; self.save()
            return dict(cfg)
        except (ValueError, OSError):
            messagebox.showerror('設定錯誤', '請輸入非負有限數字；掃描間隔至少2秒，提醒間隔至少10秒；漲幅下限不可大於上限。')

    def idle(self):
        from tkinter import messagebox
        if self.auto_active or (self.worker_thread and self.worker_thread.is_alive()):
            messagebox.showinfo('正在執行', '請先停止，等待本次辨識完成再操作。'); return False
        return True

    def install(self):
        if not self.idle(): return
        if getattr(sys, 'frozen', False):
            self.say('辨識元件已內建。' if self.dependencies() else '辨識元件缺少，請重新安裝最新版。'); return
        self.status.set('安裝辨識元件中，需網路，可能需要數分鐘…')
        self.stop_event = threading.Event()
        token = self.generation
        def job():
            try:
                proc = subprocess.run([sys.executable, '-m', 'pip', 'install', 'Pillow', 'mss', 'rapidocr_onnxruntime==1.4.4'], capture_output=True, text=True, errors='replace')
                self.events.put((token, 'message', '元件安裝完成。可框選排行榜。' if proc.returncode == 0 else '安裝失敗：' + (proc.stderr or proc.stdout)[-900:]))
            except Exception as exc: self.events.put((token, 'message', str(exc)))
        self.worker_thread = threading.Thread(target=job, daemon=True); self.worker_thread.start()

    def dependencies(self):
        from tkinter import messagebox
        missing = [m for m in ['PIL', 'mss', 'rapidocr_onnxruntime'] if importlib.util.find_spec(m) is None]
        if missing:
            messagebox.showinfo('缺少辨識元件', '先按「安裝辨識元件」。\n缺少：' + ', '.join(missing)); return False
        return True

    def select_region(self):
        if not self.idle() or not self.dependencies(): return
        from PIL import Image, ImageTk
        import mss
        with mss.mss() as cap:
            mon = cap.monitors[0]
            shot = cap.grab(mon)
            pic = Image.frombytes('RGB', shot.size, shot.rgb)
        top = self.tk.Toplevel(self.root)
        top.overrideredirect(True); top.attributes('-topmost', True)
        top.geometry(f"{mon['width']}x{mon['height']}{mon['left']:+d}{mon['top']:+d}")
        canvas = self.tk.Canvas(top, highlightthickness=0, cursor='cross'); canvas.pack(fill='both', expand=True)
        top.photo = ImageTk.PhotoImage(pic); canvas.create_image(0, 0, image=top.photo, anchor='nw')
        canvas.create_text(25, 25, anchor='nw', text='拖曳框選排行榜｜Esc取消', fill='#ff335e', font=('Microsoft JhengHei', 20, 'bold'))
        rect, start = [None], [None]
        def down(e):
            start[0] = (e.x, e.y)
            if rect[0]: canvas.delete(rect[0])
            rect[0] = canvas.create_rectangle(e.x, e.y, e.x, e.y, outline='#ff335e', width=3)
        def move(e):
            if start[0]: canvas.coords(rect[0], *start[0], e.x, e.y)
        def up(e):
            if not start[0]: return
            x,y = start[0]; left, upper = min(x,e.x), min(y,e.y)
            w,h = abs(x-e.x), abs(y-e.y)
            if w < 100 or h < 40: return
            self.config['region'] = dict(left=mon['left']+left, top=mon['top']+upper, width=w, height=h)
            self.save(); top.destroy(); self.status.set(f'已框選 {w}×{h} 像素。請校正欄位，避免視窗遮住排行榜。')
        canvas.bind('<ButtonPress-1>', down); canvas.bind('<B1-Motion>', move); canvas.bind('<ButtonRelease-1>', up)
        top.bind('<Escape>', lambda e: top.destroy()); top.focus_force()

    def calibrate(self):
        if not self.idle() or not self.dependencies(): return
        if not self.config['region']:
            self.say('請先框選排行榜。'); return
        from PIL import Image, ImageTk
        import mss
        with mss.mss() as cap:
            shot = cap.grab(self.config['region'])
            pic = Image.frombytes('RGB', shot.size, shot.rgb)
        top = self.tk.Toplevel(self.root); top.title('依序點選欄位中心（欄位須由左至右）')
        scale = min(1, 1100 / pic.width, 500 / pic.height)
        pic = pic.resize((int(pic.width*scale), int(pic.height*scale)))
        idx, points = [0], []
        prompt = self.tk.StringVar(value='請點選：' + LABELS[0] + ' 欄位中心')
        self.ttk.Label(top, textvariable=prompt).pack(pady=10)
        canvas = self.tk.Canvas(top, width=pic.width, height=pic.height); canvas.pack()
        top.photo = ImageTk.PhotoImage(pic); canvas.create_image(0, 0, image=top.photo, anchor='nw')
        def click(e):
            percent = e.x / pic.width * 100
            if points and percent <= points[-1] + 1:
                prompt.set('欄位必須由左到右，請重新點選：' + LABELS[idx[0]]); return
            points.append(percent)
            canvas.create_line(e.x, 0, e.x, pic.height, fill='#ff335e', width=2)
            idx[0] += 1
            if idx[0] == 6:
                self.config['centers'] = points; self.save(); top.destroy(); self.status.set('六欄位置已儲存。請先以圖片測試核對數字。')
            else: prompt.set('請點選：' + LABELS[idx[0]] + ' 欄位中心（無此欄仍請點選預留位置）')
        canvas.bind('<Button-1>', click)
        self.ttk.Label(top, text='缺少換手率時可點最右側留白；股名欄仍需保留。關閉視窗可取消。').pack(pady=8)

    def scan(self, image, centers):
        import numpy as np
        from PIL import ImageOps
        from rapidocr_onnxruntime import RapidOCR
        if self.ocr is None: self.ocr = RapidOCR(det_limit_type="max",det_limit_side_len=1600,intra_op_num_threads=2,inter_op_num_threads=1,use_cls=False)
        # OCR模型接受BGR；保留彩色，避免紅綠文字轉灰後消失。
        image = image.convert('RGB').resize((image.width*2, image.height*2))
        result, _ = self.ocr(np.asarray(image)[:, :, ::-1].copy(),unclip_ratio=1.6)
        return parse_ocr(result, image.width, centers, time.time())

    def image_test(self):
        from tkinter import filedialog
        if not self.idle() or not self.dependencies(): return
        path = filedialog.askopenfilename(filetypes=[('排行榜截圖', '*.png *.jpg *.jpeg *.bmp')])
        if not path: return
        token = self.reset('圖片測試（非實盤）')
        centers = list(self.config['centers'])
        def job():
            try:
                from PIL import Image
                with Image.open(path) as im: quotes, rejected = self.scan(im, centers)
                self.events.put((token, 'test', (quotes, rejected)))
            except Exception as exc: self.events.put((token, 'message', '圖片辨識失敗：' + str(exc)))
        self.worker_thread = threading.Thread(target=job, daemon=True); self.worker_thread.start()

    def reset(self, mode):
        from price_history import PriceHistory
        self.price_history=PriceHistory()
        self.generation += 1; self.mode = mode; self.engine = Engine(); self.records = []; self.rank_rows = {}
        self.stop_event = threading.Event()
        for item in self.tree.get_children(): self.tree.delete(item)
        self.status.set(mode + '｜準備中…'); self.say(mode)
        self.observation_summary.set('正在讀取股票資料｜轉強候選待判斷')
        return self.generation

    def start(self):
        if not self.idle() or not self.dependencies(): return
        cfg = self.settings()
        if cfg is None: return
        if not cfg['region']: self.say('請先框選排行榜。'); return
        token = self.reset('實盤 OCR')
        stop = self.stop_event
        def job():
            try:
                import mss
                from PIL import Image
                with mss.mss() as cap:
                    while not stop.is_set():
                        begin = time.monotonic()
                        shot = cap.grab(cfg['region'])
                        im = Image.frombytes('RGB', shot.size, shot.rgb)
                        quotes, rejected = self.scan(im, cfg['centers'])
                        if not stop.is_set(): self.events.put((token, 'quotes', (quotes, rejected, time.monotonic()-begin, cfg)))
                        stop.wait(max(.1, cfg['interval'] - (time.monotonic()-begin)))
            except Exception as exc: self.events.put((token, 'message', '實盤已停止：' + str(exc)))
        self.worker_thread = threading.Thread(target=job, daemon=True); self.worker_thread.start()

    def demo(self):
        if not self.idle(): return
        cfg = self.settings()
        if cfg is None: return
        token = self.reset('示範／合成資料，非行情')
        seed = time.time()
        def tick(i=0):
            if token != self.generation or self.stop_event.is_set(): return
            # 每秒模擬5秒，圖上時間不可視為交易所行情時間。
            quotes = [Quote('9001', '示範甲', round(100 + min(i, 90)*.07, 2), round(2 + min(i, 90)*.07, 2), 1500+i*40+max(0,i-60)*60, 1+i*.015, seed+i*5), Quote('9002','示範乙',80,1.2,2500+i*10,.8,seed+i*5)]
            self.render(quotes, 0, 0, cfg, persist=False)
            self.root.after(1000, lambda: tick(i+1))
        tick()

    def stop(self):
        self.restore_scan_cursor()
        self.scan_phase.set('已暫停｜停止請求已送出，進度保留')
        self.phase_label.configure(fg='#ffb45e');self.scan_rate.stop()
        if self.auto_active:
            self.save_scan_snapshot();self.announce_task('掃描已暫停，進度已保留')
        self.close_task_progress()
        self.stop_event.set(); self.generation += 1
        self.status.set(self.mode + '｜已暫停，進度保留；按F8接續，或按重新開始清空本輪。'); self.say('停止請求已送出。')
        if self.auto_active:
            self.auto_active=False; self.root.deiconify()

    def change_result_filters(self):
        self.config['result_filters']={key:var.get() for key,var in self.result_filters.items()}
        self.save()
        self.draw_results()

    def draw_results(self):
        from candidate_ranking import ordered_candidates,name_eligible,hidden_result
        from price_history import PriceHistory
        history=getattr(self,'price_history',None) or PriceHistory()
        for i,label in enumerate(history.headings()):self.tree.heading(f'history{i}',text=label)
        for item in self.tree.get_children():self.tree.delete(item)
        cfg=getattr(self,'display_cfg',self.config)
        filters={key:var.get() for key,var in getattr(self,'result_filters',{}).items()}
        eligible=set()
        for (q,ratio,reason,hit),match in ordered_candidates(getattr(self,'display_rows',[]),cfg):
            show_name=name_eligible(match)
            if show_name:eligible.add(q.code)
            if hidden_result(q,cfg,filters):continue
            values=[q.code,(q.name or '股名待補') if show_name else '',match['text'],f'{q.price:.2f}',f'{q.gain:+.2f}',f'{q.volume:,.0f}','—' if q.turnover is None else f'{q.turnover:.2f}','待累積' if ratio is None else f'{ratio:.2f}',reason]
            values+=history.values(q.code)
            self.tree.insert('','end',values=values,tags=('hit',) if match['hit'] else ('near',) if match['label']=='接近候選' else ())
        self.name_eligible_codes=eligible

    def render(self, quotes, rejected, elapsed, cfg, persist):
        from tkinter import messagebox
        engine = self.auto_engines.setdefault(cfg['source_rank'], Engine()) if cfg.get('source_rank') else self.engine
        rows, alerts = engine.process(quotes, cfg)
        self.price_history.update(quotes,cfg.get('scan_cycle'))
        for item in self.tree.get_children(): self.tree.delete(item)
        batch = []
        display=rows
        if cfg.get('source_rank'):
            if cfg.get('source_partial'):
                existing={} if cfg.get('source_reset') else {row[0].code:row for row in self.rank_rows.get(cfg['source_rank'],[])}
                for row in rows:existing[row[0].code]=row
                self.rank_rows[cfg['source_rank']]=list(existing.values())
            else:self.rank_rows[cfg['source_rank']]=rows
            latest={}
            for rank,items in self.rank_rows.items():
                for q,ratio,reason,hit in items:
                    if q.code not in latest or q.ts>=latest[q.code][0].ts:
                        latest[q.code]=(q,ratio,f'{rank}｜{dt.datetime.fromtimestamp(q.ts):%H:%M:%S}｜'+reason,hit)
            display=list(latest.values())
            newest=max((q.ts for q,_,_,_ in display),default=0)
            display=[(q,ratio,reason if newest-q.ts<60 else '等待重新複查｜'+reason,hit and newest-q.ts<60) for q,ratio,reason,hit in display]
        names=getattr(getattr(self,'stock_names',None),'names',{})
        for q,_,_,_ in display:
            if q.code in names:q.name=names[q.code]
        from collections import Counter
        missing=Counter(reason for _,_,text,hit in display if not hit for reason in text.split('｜')[-1].split('；'))
        explanation='；'.join(f'{reason} {count}檔' for reason,count in missing.most_common(2))
        self.observation_summary.set(f'已辨識股票 {len(display)} 檔｜轉強候選 {sum(bool(item[3]) for item in display)} 檔'+('｜'+explanation if explanation else '｜候選股置頂'))
        pending_names=sum(not q.name for q,_,_,_ in display)
        self.observation_summary.set(self.observation_summary.get()+f'｜股名記憶 {len(names)} 檔、待補 {pending_names} 檔')
        self.display_rows=display;self.display_cfg=dict(cfg)
        self.draw_results()
        for q, ratio, reason, hit in rows:
            record = [self.mode, dt.datetime.fromtimestamp(q.ts).isoformat(timespec='seconds'), q.code, q.name, q.price, q.gain, q.volume, q.turnover, ratio, reason, json.dumps(cfg, ensure_ascii=False)]
            self.records.append(record); batch.append(record)
        self.status.set(f'{self.mode}｜本輪讀取 {len(quotes)} 檔｜略過 {rejected} 列｜辨識耗時 {elapsed:.1f} 秒｜本機掃描時間 {dt.datetime.now():%H:%M:%S}')
        if persist:
            try:
                with sqlite3.connect(BASE / 'observations.sqlite3') as conn:
                    conn.execute('CREATE TABLE IF NOT EXISTS observations (mode,ts,code,name,price,gain,volume,turnover,ratio,state,config)')
                    conn.execute('CREATE TABLE IF NOT EXISTS alerts (ts,code,name,reason,config)')
                    conn.executemany('INSERT INTO observations VALUES (?,?,?,?,?,?,?,?,?,?,?)', batch)
                    conn.executemany('INSERT INTO alerts VALUES (?,?,?,?,?)', [(q.ts,q.code,q.name,reason,json.dumps(cfg,ensure_ascii=False)) for q,reason in alerts])
            except sqlite3.Error as exc: self.say('資料寫入失敗：' + str(exc))
        for q, reason in alerts:
            self.say(f'【{self.mode}】{q.code} {q.name} {q.price:.2f} / {q.gain:+.2f}%：{reason}')
        if alerts:
            self.root.bell()
            from task_progress import CandidateNotice,fresh_candidate_alerts
            if not hasattr(self,'popup_alert_times'):self.popup_alert_times={}
            fresh=fresh_candidate_alerts(alerts,self.popup_alert_times,cfg['cooldown'])
            if fresh:
                old=getattr(self,'candidate_notice',None)
                if old:old.close()
                text='\n'.join(f'{q.code} {q.name or "股名待補"}｜{q.price:.2f}元｜{q.gain:+.2f}%｜{q.volume:,.0f}張\n{reason}' for q,reason in fresh[:3])
                if len(fresh)>3:text+=f'\n另有{len(fresh)-3}檔，請查看候選清單。'
                text+='\n符合目前設定的觀察條件，不代表一定漲停。'
                self.candidate_notice=CandidateNotice(self.root,self.tk,self.ttk,text)
                spoken='；'.join(f'{q.code} {q.name or "股名待補"}，全部條件符合' for q,_ in fresh[:3])
                if len(fresh)>3:spoken+=f'；另有{len(fresh)-3}檔符合，請查看清單'
                self.announce_task(spoken)

    def poll(self):
        try:
            while True:
                token, kind, payload = self.events.get_nowait()
                if kind=='scan_region':
                    region,ready=payload
                    try:
                        if token==self.generation:
                            notice=getattr(self,'candidate_notice',None)
                            if notice and notice.alive:
                                if region is None:notice.window.withdraw()
                                elif notice.native_hwnd:
                                    import ctypes
                                    ctypes.windll.user32.ShowWindow(notice.native_hwnd,4)
                                else:notice.window.deiconify()
                            self.show_scan_outline(region);self.root.update_idletasks()
                    finally:ready.set()
                    continue
                if token is None:
                    if kind=='voice_error':self.say('語音提醒無法播放：'+str(payload))
                    else:self.handle_update(kind, payload)
                    continue
                if token != self.generation: continue
                if kind == 'cursor_note':
                    self.say(payload)
                elif kind == 'auto_phase':
                    self.scan_phase.set(payload);self.phase_label.configure(fg='#68f5d1')
                    self.say(payload)
                elif kind == 'auto_counts':
                    panel=getattr(self,'task_panel',None)
                    if panel and panel.alive:panel.counts.set(payload)
                elif kind == 'auto_rate':
                    self.scan_rate.update(payload)
                    panel=getattr(self,'task_panel',None)
                    if payload.get('count',0)>0 and panel and panel.alive:panel.mark_success()
                elif kind == 'auto_status':
                    self.status.set(payload); self.say(payload); self.update_task_progress(payload)
                elif kind == 'auto_progress':
                    detail,completed,total=payload
                    self.status.set(detail); self.update_task_progress(detail,completed,total)
                elif kind == 'auto_setup_done':
                    cfg,target,count,start_after=payload
                    self.auto_active=False;self.auto_hwnd=target;self.config=cfg
                    self.scan_phase.set('設定完成｜準備連續掃描' if start_after else '設定完成｜按 F8 開始連續掃描')
                    self.save();self.status.set(f'自動設定完成｜已驗證 {count} 檔資料｜F8 開始／停止')
                    self.say('已自動定位選單、表格與六欄；預設輪切五個排行，逐段捲動並複查末端；持續重複觀察轉強條件。')
                    if start_after:
                        self.update_task_progress('自動設定完成，準備啟動輪切掃描…')
                        self.root.after(300,lambda:self.start_auto(announce=False))
                    else:self.announce_task('自動設定完成');self.close_task_progress();self.root.deiconify()
                elif kind == 'auto_end':
                    self.scan_phase.set('測試完成｜按 F8 開始連續掃描' if payload=='輪切測試完成' else '已暫停｜進度已保留，按 F8 接續')
                    self.phase_label.configure(fg='#ffb45e')
                    self.scan_rate.stop()
                    self.save_scan_snapshot()
                    self.announce_task('任務執行失敗，請查看訊息' if '未完成' in payload or '：' in payload else '任務已停止')
                    self.close_task_progress()
                    self.auto_active=False; self.status.set(format_feedback(payload)); self.say(payload); self.root.deiconify()
                elif kind == 'auto_quotes':
                    name, quotes, rejected, elapsed, cfg = payload
                    self.mode='自動輪切／'+name
                    self.render(quotes, rejected, elapsed, cfg, persist=True)
                    if not quotes:self.say('此排行未讀到完整六欄，請核對表格及欄位校正。')
                elif kind == 'quotes': self.render(*payload, persist=True)
                elif kind == 'message': self.status.set(payload); self.say(payload)
                elif kind == 'test':
                    quotes, rejected = payload
                    for q in quotes:
                        self.tree.insert('', 'end', values=[q.code,q.name,q.price,q.gain,q.volume,q.turnover if q.turnover is not None else '—','—','圖片測試；請對照原圖'])
                    self.status.set(f'圖片測試｜讀取{len(quotes)}檔，略過{rejected}列；不寫實盤歷史、不提醒。')
        except queue.Empty: pass
        self.scan_rate_var.set(self.scan_rate.text())
        panel=getattr(self,'task_panel',None)
        if panel and panel.alive:panel.rate.set(self.scan_rate.text())
        self.root.after(150, self.poll)

    def export(self):
        from tkinter import filedialog
        path = filedialog.asksaveasfilename(defaultextension='.csv', initialfile='MitakeDetector-'+dt.datetime.now().strftime('%Y%m%d-%H%M%S')+'.csv')
        if not path: return
        try:
            with open(path, 'w', encoding='utf-8-sig', newline='') as f:
                writer = csv.writer(f); writer.writerow(['模式','本機讀取時間','股號','股名','成交價','漲幅%','總量張','換手率%','5分量速比','判斷','設定'])
                for row in self.records:
                    # 避免OCR名稱被Excel當作公式。
                    writer.writerow(["'"+v if isinstance(v,str) and v[:1] in '=+-@\t\r' else v for v in row])
            self.say('已匯出：'+path)
        except OSError as exc: self.say('匯出失敗：'+str(exc))

    def set_update_progress(self,state):
        from update_progress import UpdateProgress
        panel=getattr(self,'update_panel',None)
        if panel is None:
            self.update_panel=panel=UpdateProgress(self.root,self.tk,self.ttk)
        panel.update(state)
        self.update_bar.pack(side='left',padx=5)
        if state['stage'] in ('checking','connecting','verifying'):
            if str(self.update_bar.cget('mode'))!='indeterminate':
                self.update_bar.stop();self.update_bar.configure(mode='indeterminate');self.update_bar.start(70)
        else:
            self.update_bar.stop();self.update_bar.configure(mode='determinate')
            if state['stage']=='downloading':self.update_bar['value']=state['percent']
            elif state['stage'] in ('ready','latest'):self.update_bar['value']=100
        self.update_status.set(panel.stage.get()+(' '+panel.amount.get() if state['stage']=='downloading' else ''))

    def check_update(self):
        if self.update_busy:
            panel=getattr(self,'update_panel',None)
            if panel:panel.show()
            return
        if not self.idle(): return
        self.update_busy = True
        self.set_update_progress(dict(stage='checking',message='正在連線檢查可用版本'))
        self.update_panel.show()
        def job():
            try:
                from updater import check_update
                self.events.put((None, 'update_checked', check_update(VERSION)))
            except Exception as exc:
                self.events.put((None, 'update_error', str(exc)))
        threading.Thread(target=job, daemon=True).start()

    def handle_update(self, kind, payload):
        from tkinter import messagebox
        if kind == 'update_error':
            self.update_busy = False
            self.set_update_progress(dict(stage='error',message=str(payload)+'；可按一鍵更新重試'))
            self.say('更新失敗：' + payload)
            messagebox.showerror('更新未完成', payload)
        elif kind == 'update_progress':
            self.set_update_progress(payload)
        elif kind == 'update_checked':
            self.update_busy = False
            if payload is None:
                self.set_update_progress(dict(stage='latest',message=f'目前版本 v{VERSION}'))
                messagebox.showinfo('檢查更新', f'目前版本 v{VERSION}，已是最新穩定版。')
                return
            self.say(f"發現新版 v{payload['version']}，開始下載；驗證成功後自動安裝並重新啟動。")
            self.download_update(payload)
        elif kind == 'update_downloaded':
            self.update_busy = False
            self.set_update_progress(dict(stage='ready',message='大小與SHA-256驗證通過，即將安裝並重新開啟'))
            self.root.update_idletasks()
            if sys.platform != 'win32' or not getattr(sys, 'frozen', False):
                self.update_status.set('安裝檔已下載並驗證')
                messagebox.showinfo('下載完成', '已下載並驗證：'+str(payload)+'\n請在 Windows 上開啟安裝。')
                return
            try:
                from updater import launch_installer
                launch_installer(payload)
                self.close()
            except OSError as exc:
                self.set_update_progress(dict(stage='error',message='安裝啟動失敗：'+str(exc)))
                messagebox.showerror('安裝啟動失敗', str(exc))

    def download_update(self, manifest):
        if self.update_busy: return
        self.update_busy = True
        version=manifest['version']
        self.set_update_progress(dict(stage='connecting',version=version,message='正在建立下載連線'))
        self.update_panel.show()
        def job():
            try:
                from updater import download_installer
                previous=[-1,0.0]
                def transfer(downloaded,total,elapsed):
                    percent=min(100,int(downloaded*100/total))
                    if percent!=previous[0] or elapsed-previous[1]>=.2:
                        previous[:]=[percent,elapsed]
                        self.events.put((None,'update_progress',dict(stage='downloading',version=version,percent=percent,downloaded=downloaded,total=total,elapsed=elapsed,message='下載完成後會自動驗證並安裝')))
                def stage(value):
                    self.events.put((None,'update_progress',dict(stage=value,version=version,message={'connecting':'正在連線至下載伺服器','verifying':'正在核對檔案大小與SHA-256','ready':'驗證成功，準備啟動安裝程式'}[value])))
                path = download_installer(manifest, BASE / 'updates', transfer=transfer,stage=stage)
                self.events.put((None, 'update_downloaded', path))
            except Exception as exc:
                self.events.put((None, 'update_error', str(exc)))
        threading.Thread(target=job, daemon=True).start()

    def open_folder(self):
        if sys.platform == 'win32': os.startfile(str(BASE))
        else: self.say(str(BASE))

    def show_version_history(self):
        from version_history import history_text
        top=self.tk.Toplevel(self.root);top.title('版本迭代日誌');top.geometry('900x700')
        frame=self.ttk.Frame(top);frame.pack(fill='both',expand=True,padx=15,pady=15)
        text=self.tk.Text(frame,wrap='word',font=('Microsoft JhengHei',11),bg='#101a2c',fg='#edf2ff',padx=16,pady=12)
        scroll=self.ttk.Scrollbar(frame,command=text.yview);text.configure(yscrollcommand=scroll.set)
        scroll.pack(side='right',fill='y');text.pack(fill='both',expand=True)
        text.insert('1.0',history_text(BASE));text.configure(state='disabled')
        return top

    def help(self):
        top = self.tk.Toplevel(self.root); top.title('使用說明與限制'); top.geometry('850x650')
        text = self.tk.Text(top, wrap='word', font=('Microsoft JhengHei', 11)); text.pack(fill='both', expand=True)
        text.insert('1.0', HELP); text.configure(state='disabled')

    def close(self):
        self.restore_scan_cursor()
        if self.auto_active:self.save_scan_snapshot()
        voice=getattr(self,'task_voice',None)
        if voice:voice.close()
        self.close_task_progress()
        panel=getattr(self,'update_panel',None)
        if panel:panel.close()
        self.update_bar.stop()
        self.stop_event.set(); self.generation += 1; self.root.destroy()


def self_test():
    import unittest, io
    if sys.stderr is None: sys.stderr = io.StringIO()
    class Tests(unittest.TestCase):
        def test_numbers(self):
            self.assertEqual(number('１,２００'), 1200)
            self.assertEqual(number('−1.2%'), -1.2)
            for bad in ['1.2萬', '1O0', 'NaN', '1 2 3x']:
                with self.assertRaises(ValueError): number(bad)
        def test_ocr_missing_column_never_shifts(self):
            def cell(x, y, value): return ([[x-2,y-5],[x+2,y-5],[x+2,y+5],[x-2,y+5]], value, .99)
            cells = [cell(x,20,v) for x,v in zip([6,22,40,57,75,92],['2330','測試','100','2.0%','1,500','1.5'])]
            quotes, rejected = parse_ocr(cells,100,DEFAULT['centers'],1)
            self.assertEqual(len(quotes),1); self.assertEqual(quotes[0].volume,1500)
            quotes, rejected = parse_ocr(cells[:3]+cells[4:],100,DEFAULT['centers'],1)
            self.assertEqual(quotes,[]); self.assertEqual(rejected,1)
        def test_breakout_confirmation_and_cooldown(self):
            e=Engine(); cfg=dict(DEFAULT)
            t=dt.datetime(2026,10,16,9).timestamp()
            def run(price, sec): return e.process([Quote('2330','測試',price,2,1500+sec,None,t+sec)],cfg)
            self.assertFalse(run(100,0)[1]); self.assertFalse(run(101,5)[1])
            self.assertEqual(len(run(101,10)[1]),1); self.assertFalse(run(101,15)[1])
        def test_volume_regression_is_rejected(self):
            e=Engine(); cfg=dict(DEFAULT,use_breakout=False)
            e.process([Quote('2330','測試',100,2,1500,None,100)],cfg)
            rows,alerts=e.process([Quote('2330','測試',100,2,1400,None,105)],cfg)
            self.assertFalse(alerts); self.assertIn('量倒退',rows[0][2]); self.assertEqual(len(e.history['2330']),1)
        def test_acceleration_warmup(self):
            e=Engine(); cfg=dict(DEFAULT,use_breakout=False,use_accel=True)
            ratios=[]
            for sec in range(0,601,5):
                v=2000+sec if sec<=300 else 2300+(sec-300)*2
                rows,_=e.process([Quote('2330','測試',100,2,v,None,10000+sec)],cfg)
                ratios.append(rows[0][1])
            self.assertTrue(all(x is None for x in ratios[:-1])); self.assertAlmostEqual(ratios[-1],2)
        def test_stale_blocks_alert(self):
            e=Engine(); cfg=dict(DEFAULT,use_breakout=False,cooldown=10)
            e.process([Quote('2330','測試',100,2,1500,None,10000)],cfg)
            rows,alerts=e.process([Quote('2330','測試',100,2,1500,None,10065)],cfg)
            self.assertFalse(alerts); self.assertIn('60秒',rows[0][2])
        def test_turnover_missing_blocks(self):
            e=Engine(); cfg=dict(DEFAULT,use_breakout=False,use_turnover=True,min_turnover=1)
            e.process([Quote('2330','測試',100,2,1500,None,10000)],cfg)
            self.assertFalse(e.process([Quote('2330','測試',100,2,1501,None,10005)],cfg)[1])
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(Tests)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful(): sys.exit(1)


if __name__ == '__main__':
    if '--cursor-guard' in sys.argv:
        from cursor_visibility import run_guard
        try:
            index=sys.argv.index('--cursor-guard')
            run_guard(int(sys.argv[index+1]),sys.argv[index+2])
        except Exception:sys.exit(1)
    elif '--runtime-smoke' in sys.argv:
        directory=Path(getattr(sys,'_MEIPASS',Path(__file__).parent))
        report=Path(sys.argv[sys.argv.index('--runtime-smoke')+1])
        report.write_text(json.dumps(dict(frozen=bool(getattr(sys,'frozen',False)),runtime=str(directory),python_dll=(directory/'python311.dll').is_file())),encoding='utf-8')
    elif '--restart-smoke' in sys.argv:
        from updater import spawn_independent
        report=sys.argv[sys.argv.index('--restart-smoke')+1]
        os.environ['_PYI_APPLICATION_HOME_DIR']=str(Path(report).parent/'missing-old-MEI')
        spawn_independent([sys.executable,'--runtime-smoke',report])
    elif '--smoke-test' in sys.argv:
        from smoke_test import run_smoke_test
        run_smoke_test(Path(sys.argv[sys.argv.index('--smoke-test') + 1]))
    elif '--self-test' in sys.argv:
        self_test()
    else:
        if sys.platform == 'win32':
            try:
                import ctypes
                ctypes.windll.shcore.SetProcessDpiAwareness(2)
            except Exception: pass
        if sys.platform == 'win32':
            import ctypes
            _app_mutex = ctypes.windll.kernel32.CreateMutexW(None, False, 'Local\\RexMitakeDetector')
            if ctypes.windll.kernel32.GetLastError() == 183:
                ctypes.windll.user32.MessageBoxW(None, '偵測器已在執行，請從工作列開啟。', '漲停板探測器-雷允澤製', 64)
                sys.exit(0)
        import tkinter as tk
        root = tk.Tk(); App(root); root.mainloop()
