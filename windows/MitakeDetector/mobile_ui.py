"""Pair a companion without blocking or manipulating the scan worker."""
from urllib.parse import urlencode
class MobileUI:
    def get_mobile_bridge(self):
        bridge=getattr(self,'mobile_bridge',None)
        if bridge is None:
            from mobile_bridge import MobileBridge
            from MitakeDetector import BASE
            self.mobile_bridge=bridge=MobileBridge(BASE/'mobile-pairing.json')
        return bridge
    def show_mobile_pairing(self):
        from tkinter import messagebox
        from mobile_bridge import local_addresses
        top=self.tk.Toplevel(self.root);top.title('手機連動｜漲停雷達');top.geometry('580x690')
        self.ttk.Label(top,text='手機連動',font=('Microsoft JhengHei',22,'bold')).pack(pady=(20,4))
        self.ttk.Label(top,text='同 Wi-Fi 或私人 VPN｜開啟後手機可讀取候選與接收通知',wraplength=520).pack(padx=20)
        self.ttk.Label(top,text='通知需開啟六項條件。5/6、6/6為觀察訊號；6/6仍須確認必要檢核。',wraplength=520).pack(padx=20,pady=6)
        address=self.tk.StringVar(value=local_addresses()[0]);state=self.tk.StringVar(value='尚未啟用連線服務')
        self.ttk.Combobox(top,textvariable=address,values=local_addresses(),state='readonly').pack(pady=8)
        qr=self.tk.Label(top);qr.pack()
        pair=self.tk.StringVar();entry=self.ttk.Entry(top,textvariable=pair);entry.pack(fill='x',padx=20,pady=8)
        self.ttk.Label(top,textvariable=state,wraplength=520).pack(padx=20,pady=6)
        def refresh(*args):
            bridge=self.get_mobile_bridge()
            if not bridge.server:return
            url='mitake://pair?'+urlencode(dict(address=f'http://{address.get()}:{bridge.port}',token=bridge.token))
            pair.set(url)
            import qrcode
            from PIL import ImageTk,Image
            im=qrcode.make(url).convert('RGB').resize((290,290),Image.Resampling.NEAREST)
            top.qr_image=ImageTk.PhotoImage(im);qr.configure(image=top.qr_image)
            state.set(f'已開啟｜{address.get()}:{bridge.port}\n手機開啟「漲停雷達」→掃描配對碼。若無法連線，請允許Windows私人網路的防火牆提示；外出可使用私人VPN。')
        def start():
            try:self.get_mobile_bridge().start();refresh()
            except (OSError,ImportError) as error:messagebox.showerror('連線未完成',str(error),parent=top)
        def stop():
            self.get_mobile_bridge().close();qr.configure(image='');pair.set('');state.set('已停止手機連線；電腦掃描持續')
        def rotate():
            self.get_mobile_bridge().rotate();refresh();state.set('已重設配對碼；原手機須重新配對')
        buttons=self.ttk.Frame(top);buttons.pack(pady=6)
        for label,fn in [('啟用／顯示配對碼',start),('停止連線',stop),('重設配對碼',rotate)]:self.ttk.Button(buttons,text=label,command=fn).pack(side='left',padx=3)
        self.ttk.Button(top,text='複製配對連結',command=lambda:(top.clipboard_clear(),top.clipboard_append(pair.get()))).pack(pady=6)
        address.trace_add('write',refresh)
        if self.get_mobile_bridge().server:refresh()
        return top
