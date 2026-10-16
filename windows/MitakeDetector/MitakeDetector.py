# -*- coding: utf-8 -*-
"""三竹盤中轉強偵測器 0.2.0 — Python 3.11/3.12 Windows 桌面原型。
啟動：python MitakeDetector.py；核心驗證：python MitakeDetector.py --self-test
首次按「安裝辨識元件」，再「框選排行榜」「校正欄位」「開始實盤」。
本程式採可見畫面 OCR，沒有三竹 SDK 登入或官方行情串接。
"""
import csv, dataclasses, datetime as dt, importlib.util, json, math, os
import queue, random, re, sqlite3, subprocess, sys, threading, time, unicodedata
from collections import deque
from auto_ui import AutoUI
from pathlib import Path

VERSION = '0.3.0'
FIELDS = ['code', 'name', 'price', 'gain', 'volume', 'turnover']
LABELS = ['股號', '股名', '成交價', '漲幅%', '總量（張）', '換手率%']
BASE = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'MitakeDetector'
DEFAULT = dict(interval=5, min_price=50, min_volume=1000, min_gain=1,
               max_gain=8.5, min_turnover=0, use_turnover=False,
               use_accel=False, accel=1.5, use_breakout=True, cooldown=300,
               centers=[6, 22, 40, 57, 75, 92], region=None, auto_config=None)

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
            reason = '符合設定的轉強條件' if hit else '；'.join(reasons)
            if hit and q.ts - self.last_alert.get(q.code, -1e20) >= config['cooldown']:
                self.last_alert[q.code] = q.ts
                alerts.append((q, reason))
            rows.append((q, ratio, reason, hit))
        return rows, alerts


HELP = '''三竹盤中轉強偵測器 v0.3.0

【Windows 安裝版】
開啟 MitakeDetector-Setup-v0.3.0.exe 安裝。支援 Windows 10/11 x64；不需Python。
安裝於目前使用者的應用程式目錄，建立開始功能表與可選的桌面捷徑。
辨識元件與OCR模型已內建，不需另外下載。

【接上三竹】
1. 先登入並開啟三竹漲跌幅排行榜。表格必須保持可見，不可遮住或最小化。
2. 三竹欄位須依序為：股號、股名、成交價、漲幅%、總量（張）、換手率%。
   股號須獨立一欄；總量以張顯示，不支援萬張縮寫。缺少換手率可不勾選該條件。
3. 按「框選排行榜」，拖曳表格範圍，Esc取消。
4. 按「校正欄位」，依序點每欄中心。欄位位置由左至右。
5. 按「開始實盤」。將偵測器視窗移到不遮住排行榜的位置。
6. 若移動或縮放三竹畫面，先停止，再重新框選校正。
圖片測試請用已裁切至相同表格範圍的截圖；圖片模式不提醒、不寫實盤資料。

【自動輪切（新增）】
1. 開啟三竹，按「自動輪切設定」，選擇正確視窗。
2. 依序框選：排行名稱下拉框、展開後的選單清單、排行榜資料表格。
   框選展開選單時，程式會使用已設定的下拉框位置展開選單供框選。
3. 勾選要輪切的排行，設定停留秒數與頁數；先測試輪切一次。
4. 表格沿用主畫面「校正欄位」的六欄位置，所有排行需使用相同欄位排列。
5. 按「開始自動輪切」後偵測器縮到工作列，由滑鼠操作三竹。
6. F8緊急停止；切到其他程式、遮住操作區、關閉/最小化或縮放三竹時自動停止。
7. 可移動三竹視窗；縮放後需重新框選。標題辨識不符時不會記入該排行資料。
輪切只使用選單點擊及滑鼠滾輪；僅收集設定頁數，不保證涵蓋完整排行榜。
尚未在使用者的實際三竹版本驗證，需先執行「測試輪切一次」。

【內建更新】
按下方「檢查更新／下載安裝」，查看版本說明後按「下載並安裝新版」。
下載自 GitHub 的此產品專用管道；會核對產品、版本、大小、SHA-256及EXE檔頭。
驗證通過後關閉程式、執行更新安裝並重開。失敗可重試，設定與紀錄保留。
更新需網路；偵測與OCR在本機執行，不傳送三竹畫面、帳號或密碼。

【規則定義】
預設：股價>=50、總量>=1000張、漲幅1~8.5%、觀測突破後兩次讀值守住突破價。
「觀測新高」僅指本輪開始後看到的最高價，不是完整當日高點。
「近5分量速比」為最近5分鐘每秒增量除以前5分鐘每秒增量，需觀察滿10分鐘。
這不是估量比，也不是過去20日相同時段量比。沒有接歷史K線及VWAP/MACD/RSI。
換手率讀取自三竹畫面；成交量及換手率不當作兩個獨立加分訊號。
連續60秒讀值不變會暫停該股提醒，可能因無成交或畫面停止更新。
讀取時間為本機掃描時間，不是交易所行情時間；OCR與畫面更新都有延遲。

【範圍與限制】
手動模式僅讀取可見列；自動模式讀取設定頁數，沒有三竹SDK/API官方串接。
離開畫面的股票可能缺少量速資料。重新開始會重新累積觀測基準。
模糊、合併或格式不明的欄位保守略過；量倒退或單次股價跳動>3%也會略過。
這是候選訊號原型，未經三竹實盤全面驗證、尚未驗證漲停命中率、不會自動下單。

【紀錄】
實盤觀測與提醒寫入本機SQLite；按「資料資料夾」查看。設定自動儲存。
「匯出本輪CSV」可用Excel開啟。示範為合成資料，不寫實盤資料庫。
不儲存螢幕截圖。更新與解除安裝均不主動刪除使用者的設定與歷史紀錄。
'''


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
        self.ocr = None
        self.update_busy = False
        self.update_status = tk.StringVar(value='')
        root.title('三竹盤中轉強偵測器 v' + VERSION)
        root.geometry('1180x860')
        root.minsize(1100, 760)
        root.configure(bg='#0b1020')
        style = ttk.Style()
        style.theme_use('clam')
        style.configure('.', background='#111a2e', foreground='#e8eefb', font=('Microsoft JhengHei', 10))
        style.configure('TButton', padding=8)
        style.configure('Treeview', background='#101a2c', fieldbackground='#101a2c', foreground='#e4eaf5', rowheight=30)
        style.map('Treeview', background=[('selected', '#7b253c')])
        tk.Label(root, text='盤中轉強  /  M I T A K E', fg='#ff5879', bg='#0b1020', font=('Microsoft JhengHei', 22, 'bold')).pack(anchor='w', padx=20, pady=(15, 3))
        tk.Label(root, text='畫面辨識版 · 候選訊號提醒 · 尚未驗證漲停命中率', fg='#9caeca', bg='#0b1020').pack(anchor='w', padx=20)
        bar = ttk.Frame(root); bar.pack(fill='x', padx=15, pady=12)
        for label, action in [('辨識元件檢查', self.install), ('框選排行榜', self.select_region), ('校正欄位', self.calibrate), ('圖片測試', self.image_test), ('示範', self.demo), ('開始實盤', self.start), ('停止', self.stop), ('使用說明', self.help)]:
            ttk.Button(bar, text=label, command=action).pack(side='left', padx=3)
        auto_bar = ttk.Frame(root); auto_bar.pack(fill='x', padx=18, pady=(0, 8))
        ttk.Button(auto_bar, text='自動輪切設定', command=self.configure_auto).pack(side='left')
        ttk.Button(auto_bar, text='開始自動輪切', command=self.start_auto).pack(side='left', padx=8)
        ttk.Label(auto_bar, text='F8 停止 · 輪切會使用滑鼠 · 離開三竹視窗自動停止').pack(side='left', padx=8)
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
        self.status = tk.StringVar(value='尚未連接畫面。可先按「示範」；實盤請先框選與校正。')
        tk.Label(root, textvariable=self.status, bg='#0b1020', fg='#58ddd0', anchor='w', wraplength=1120).pack(fill='x', padx=20, pady=10)
        table_frame = ttk.Frame(root); table_frame.pack(fill='both', expand=True, padx=18)
        cols = ['code','name','price','gain','volume','turnover','ratio','state']
        self.tree = ttk.Treeview(table_frame, columns=cols, show='headings')
        for key, label, width in zip(cols, LABELS + ['5分量速比', '判斷／缺少條件'], [75,100,90,75,100,85,100,380]):
            self.tree.heading(key, text=label); self.tree.column(key, width=width, stretch=key == 'state')
        self.tree.tag_configure('hit', foreground='#ff738d')
        sb = ttk.Scrollbar(table_frame, orient='vertical', command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side='left', fill='both', expand=True); sb.pack(side='right', fill='y')
        self.log = tk.Text(root, height=5, bg='#0a1322', fg='#a6b8d2', font=('Microsoft JhengHei', 10), state='disabled')
        self.log.pack(fill='x', padx=18, pady=8)
        foot = ttk.Frame(root); foot.pack(fill='x', padx=18, pady=(0,12))
        ttk.Button(foot, text='匯出本輪 CSV', command=self.export).pack(side='left')
        ttk.Button(foot, text='資料資料夾', command=self.open_folder).pack(side='left', padx=8)
        ttk.Button(foot, text='檢查更新／下載安裝', command=self.check_update).pack(side='left', padx=8)
        ttk.Label(foot, textvariable=self.update_status).pack(side='left')
        ttk.Label(foot, text='僅偵測可見列 · 請保持排行榜可見 · 不會自動下單').pack(side='right')
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.after(150, self.poll)

    def say(self, text):
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
        if self.ocr is None: self.ocr = RapidOCR()
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
        self.generation += 1; self.mode = mode; self.engine = Engine(); self.records = []
        self.stop_event = threading.Event()
        for item in self.tree.get_children(): self.tree.delete(item)
        self.status.set(mode + '｜準備中…'); self.say(mode)
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
        self.stop_event.set(); self.generation += 1
        self.status.set(self.mode + '｜已停止；保留本輪紀錄，可匯出。'); self.say('停止請求已送出。')
        if self.auto_active:
            self.auto_active=False; self.root.deiconify()

    def render(self, quotes, rejected, elapsed, cfg, persist):
        from tkinter import messagebox
        engine = self.auto_engines.setdefault(cfg['source_rank'], Engine()) if cfg.get('source_rank') else self.engine
        rows, alerts = engine.process(quotes, cfg)
        for item in self.tree.get_children(): self.tree.delete(item)
        batch = []
        for q, ratio, reason, hit in sorted(rows, key=lambda item: (not item[3], -item[0].gain)):
            values = [q.code, q.name, f'{q.price:.2f}', f'{q.gain:+.2f}', f'{q.volume:,.0f}', '—' if q.turnover is None else f'{q.turnover:.2f}', '待累積' if ratio is None else f'{ratio:.2f}', reason]
            self.tree.insert('', 'end', values=values, tags=('hit',) if hit else ())
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
        if alerts: self.root.bell()

    def poll(self):
        if self.auto_active and sys.platform=='win32':
            import ctypes
            if ctypes.windll.user32.GetAsyncKeyState(0x77)&0x8000:self.stop()
        try:
            while True:
                token, kind, payload = self.events.get_nowait()
                if token is None:
                    self.handle_update(kind, payload)
                    continue
                if token != self.generation: continue
                if kind == 'auto_status':
                    self.status.set(payload); self.say(payload)
                elif kind == 'auto_end':
                    self.auto_active=False; self.status.set(payload); self.say(payload); self.root.deiconify()
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

    def check_update(self):
        if self.update_busy: return
        self.update_busy = True
        self.update_status.set('檢查更新中…')
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
            self.update_status.set('更新失敗，可重試')
            self.say('更新失敗：' + payload)
            messagebox.showerror('更新未完成', payload)
        elif kind == 'update_progress':
            self.update_status.set(f'下載新版 {payload}%')
        elif kind == 'update_checked':
            self.update_busy = False
            if payload is None:
                self.update_status.set('目前已是最新版')
                messagebox.showinfo('檢查更新', f'目前版本 v{VERSION}，已是最新穩定版。')
                return
            top = self.tk.Toplevel(self.root)
            top.title('發現新版本')
            top.geometry('580x360')
            self.ttk.Label(top, text=f"目前 v{VERSION} → 新版 v{payload['version']}", font=('Microsoft JhengHei', 14, 'bold')).pack(pady=15)
            self.ttk.Label(top, text=f"安裝檔 {payload['size']/1024/1024:.1f} MB；下載完成後驗證並啟動安裝。", wraplength=540).pack(padx=15)
            text = self.tk.Text(top, height=8, wrap='word')
            text.pack(fill='both', expand=True, padx=15, pady=10)
            text.insert('1.0', str(payload.get('notes', ''))[:12000]);text.configure(state='disabled')
            def install_now():
                top.destroy(); self.download_update(payload)
            self.ttk.Button(top, text='下載並安裝新版', command=install_now).pack(pady=10)
        elif kind == 'update_downloaded':
            self.update_busy = False
            if sys.platform != 'win32' or not getattr(sys, 'frozen', False):
                self.update_status.set('安裝檔已下載並驗證')
                messagebox.showinfo('下載完成', '已下載並驗證：'+str(payload)+'\n請在 Windows 上開啟安裝。')
                return
            try:
                subprocess.Popen([str(payload), '/SP-', '/SILENT', '/NORESTART', '/CLOSEAPPLICATIONS', '/RESTARTAPPLICATIONS', '/LAUNCHAPP=1'], close_fds=True)
                self.close()
            except OSError as exc:
                self.update_status.set('安裝啟動失敗')
                messagebox.showerror('安裝啟動失敗', str(exc))

    def download_update(self, manifest):
        if self.update_busy: return
        self.update_busy = True
        self.update_status.set('開始下載新版…')
        def job():
            try:
                from updater import download_installer
                previous = [-1]
                def progress(value):
                    if value // 10 != previous[0] // 10:
                        previous[0] = value
                        self.events.put((None, 'update_progress', value))
                path = download_installer(manifest, BASE / 'updates', progress)
                self.events.put((None, 'update_downloaded', path))
            except Exception as exc:
                self.events.put((None, 'update_error', str(exc)))
        threading.Thread(target=job, daemon=True).start()

    def open_folder(self):
        if sys.platform == 'win32': os.startfile(str(BASE))
        else: self.say(str(BASE))

    def help(self):
        top = self.tk.Toplevel(self.root); top.title('使用說明與限制'); top.geometry('850x650')
        text = self.tk.Text(top, wrap='word', font=('Microsoft JhengHei', 11)); text.pack(fill='both', expand=True)
        text.insert('1.0', HELP); text.configure(state='disabled')

    def close(self):
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
    if '--smoke-test' in sys.argv:
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
                ctypes.windll.user32.MessageBoxW(None, '偵測器已在執行，請從工作列開啟。', '三竹盤中轉強偵測器', 64)
                sys.exit(0)
        import tkinter as tk
        root = tk.Tk(); App(root); root.mainloop()
