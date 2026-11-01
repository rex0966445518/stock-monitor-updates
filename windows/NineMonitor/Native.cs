using System;
using System.Drawing;
using System.Windows.Forms;
using System.Runtime.InteropServices;

namespace NineMonitor {
    public static class Desktop {
        [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
        [DllImport("user32.dll", EntryPoint="SetProcessDpiAwarenessContext")] static extern bool SetDpiContext(IntPtr context);
        public static void EnableDpiAwareness() {
            try { if(SetDpiContext(new IntPtr(-4))) return; } catch(EntryPointNotFoundException) { }
            SetProcessDPIAware();
        }
        [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);
        public static Bitmap Capture(Rectangle rect) {
            Bitmap b = new Bitmap(rect.Width, rect.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
            try { using (Graphics g = Graphics.FromImage(b)) g.CopyFromScreen(rect.Location, Point.Empty, rect.Size, CopyPixelOperation.SourceCopy); return b; }
            catch { b.Dispose(); throw; }
        }
    }
    public sealed class Selector : Form {
        public string Prompt = "拖曳框住指定顏色的一個數字，放開確認；ESC 取消";
        Bitmap shot; Point start; Rectangle selected; bool dragging;
        public Rectangle Selected { get; private set; }
        public Selector() {
            Rectangle desktop = SystemInformation.VirtualScreen;
            shot = Desktop.Capture(desktop);
            FormBorderStyle = FormBorderStyle.None; StartPosition = FormStartPosition.Manual;
            Bounds = desktop; TopMost = true; ShowInTaskbar = false; DoubleBuffered = true; KeyPreview = true; Cursor = Cursors.Cross;
        }
        protected override void OnPaint(PaintEventArgs e) {
            e.Graphics.DrawImageUnscaled(shot, 0, 0);
            using (Brush shade = new SolidBrush(Color.FromArgb(120,0,0,0))) e.Graphics.FillRectangle(shade, ClientRectangle);
            if (selected.Width > 0 && selected.Height > 0) {
                e.Graphics.DrawImage(shot, selected, selected, GraphicsUnit.Pixel);
                using (Pen pen = new Pen(Color.Lime, 2)) e.Graphics.DrawRectangle(pen, selected);
            }
            using (Font font = new Font("Microsoft JhengHei", 16, FontStyle.Bold))
            using (Brush brush = new SolidBrush(Color.White))
                e.Graphics.DrawString(Prompt, font, brush, 24, 24);
        }
        protected override void OnMouseDown(MouseEventArgs e) { if(e.Button == MouseButtons.Left) { start=e.Location; dragging=true; } }
        protected override void OnMouseMove(MouseEventArgs e) {
            if (!dragging) return;
            int x=Math.Max(0,Math.Min(ClientSize.Width,e.X)), y=Math.Max(0,Math.Min(ClientSize.Height,e.Y));
            selected=Rectangle.FromLTRB(Math.Min(start.X,x),Math.Min(start.Y,y),Math.Max(start.X,x),Math.Max(start.Y,y)); Invalidate();
        }
        protected override void OnMouseUp(MouseEventArgs e) {
            if (!dragging) return; dragging=false;
            if(selected.Width < 30 || selected.Height < 20) return;
            Selected=new Rectangle(selected.X+Left,selected.Y+Top,selected.Width,selected.Height);
            DialogResult=DialogResult.OK; Close();
        }
        protected override void OnKeyDown(KeyEventArgs e) { if(e.KeyCode==Keys.Escape) { DialogResult=DialogResult.Cancel; Close(); } }
        protected override void Dispose(bool disposing) { if(disposing && shot!=null) {shot.Dispose();shot=null;} base.Dispose(disposing); }
    }
    public sealed class Border : Form {
        public Border(Rectangle region) {
            FormBorderStyle=FormBorderStyle.None; ShowInTaskbar=false; StartPosition=FormStartPosition.Manual; TopMost=true;
            Bounds=Rectangle.Inflate(region,3,3); BackColor=Color.Magenta; TransparencyKey=Color.Magenta;
        }
        protected override bool ShowWithoutActivation { get { return true; } }
        protected override CreateParams CreateParams {
            get { CreateParams cp=base.CreateParams; cp.ExStyle|=0x00000020|0x00080000|0x08000000|0x00000080; return cp; }
        }
        protected override void OnPaint(PaintEventArgs e) {
            using(Pen p=new Pen(Color.Lime,2)) e.Graphics.DrawRectangle(p,1,1,ClientSize.Width-3,ClientSize.Height-3);
        }
    }
    public sealed class EscapeKey : NativeWindow, IDisposable {
        [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr hwnd,int id,uint modifiers,uint key);
        [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr hwnd,int id);
        public event EventHandler Pressed;
        bool registered;
        public bool Register() {
            if(registered) return true;
            if(Handle==IntPtr.Zero) CreateHandle(new CreateParams { Parent=new IntPtr(-3) });
            registered=RegisterHotKey(Handle,1,0,27);return registered;
        }
        public void Unregister() { if(registered) UnregisterHotKey(Handle,1);registered=false; }
        protected override void WndProc(ref Message m) { if(m.Msg==0x0312 && Pressed!=null) Pressed(this,EventArgs.Empty);base.WndProc(ref m); }
        public void Dispose() { Unregister();if(Handle!=IntPtr.Zero) DestroyHandle(); }
    }

    public sealed class PixelPicker : Form {
        Bitmap shot;
        public Color Picked { get; private set; }
        public PixelPicker() {
            Rectangle desk=SystemInformation.VirtualScreen;shot=Desktop.Capture(desk);
            FormBorderStyle=FormBorderStyle.None;StartPosition=FormStartPosition.Manual;Bounds=desk;
            TopMost=true;ShowInTaskbar=false;Cursor=Cursors.Cross;KeyPreview=true;DoubleBuffered=true;
        }
        protected override void OnPaint(PaintEventArgs e) {
            e.Graphics.DrawImageUnscaled(shot,0,0);
            using(Brush b=new SolidBrush(Color.FromArgb(200,0,0,0))) e.Graphics.FillRectangle(b,0,0,Width,60);
            using(Font f=new Font("Microsoft JhengHei",16,FontStyle.Bold)) e.Graphics.DrawString("點選數字筆畫中央來取色；ESC 取消",f,Brushes.White,24,15);
        }
        protected override void OnMouseDown(MouseEventArgs e) {
            if(e.Button!=MouseButtons.Left || e.Y<60)return;
            Picked=shot.GetPixel(Math.Max(0,Math.Min(shot.Width-1,e.X)),Math.Max(0,Math.Min(shot.Height-1,e.Y)));
            DialogResult=DialogResult.OK;Close();
        }
        protected override void OnKeyDown(KeyEventArgs e) {if(e.KeyCode==Keys.Escape){DialogResult=DialogResult.Cancel;Close();}}
        protected override void Dispose(bool disposing){if(disposing && shot!=null){shot.Dispose();shot=null;}base.Dispose(disposing);}
    }
    public static class ColorFilter {
        public static bool Matches(Color c,string kind,int customRgb) {
            double r=c.R/255.0,g=c.G/255.0,b=c.B/255.0;
            double max=Math.Max(r,Math.Max(g,b)),min=Math.Min(r,Math.Min(g,b));
            double sat=max==0?0:(max-min)/max; double hue=c.GetHue();
            if(customRgb>=0) {
                Color target=Color.FromArgb((customRgb>>16)&255,(customRgb>>8)&255,customRgb&255);
                double tr=target.R/255.0,tg=target.G/255.0,tb=target.B/255.0;
                double tm=Math.Max(tr,Math.Max(tg,tb)),tn=Math.Min(tr,Math.Min(tg,tb));
                double ts=tm==0?0:(tm-tn)/tm;
                if(ts<0.18) return sat<0.24 && max>Math.Max(0.45,tm-0.20);
                double diff=Math.Abs(hue-target.GetHue());diff=Math.Min(diff,360-diff);
                return diff<=20 && sat>=Math.Max(0.18,ts-0.4) && max>=Math.Max(0.25,tm-0.4);
            }
            if(kind=="Orange")return hue>=12 && hue<=58 && sat>=0.32 && max>=0.4;
            if(kind=="Pink")return hue>=250 && hue<=355 && sat>=0.22 && max>=0.4;
            return sat<=0.22 && max>=0.68;
        }
        public static Bitmap Mask(Bitmap source,Rectangle local,string kind,int customRgb,out int count) {
            using(Bitmap crop=source.Clone(local,System.Drawing.Imaging.PixelFormat.Format32bppArgb)) {
                var data=crop.LockBits(new Rectangle(0,0,crop.Width,crop.Height),System.Drawing.Imaging.ImageLockMode.ReadWrite,System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                count=0;
                try {
                    byte[] bytes=new byte[Math.Abs(data.Stride)*data.Height];Marshal.Copy(data.Scan0,bytes,0,bytes.Length);
                    for(int y=0;y<crop.Height;y++) for(int x=0;x<crop.Width;x++) {
                        int i=y*data.Stride+x*4;Color c=Color.FromArgb(bytes[i+2],bytes[i+1],bytes[i]);bool hit=Matches(c,kind,customRgb);
                        if(hit)count++;byte value=hit?(byte)0:(byte)255;bytes[i]=value;bytes[i+1]=value;bytes[i+2]=value;bytes[i+3]=255;
                    }
                    Marshal.Copy(bytes,0,data.Scan0,bytes.Length);
                } finally {crop.UnlockBits(data);}
                double scale=Math.Min(3.0,2100.0/Math.Max(crop.Width,crop.Height));
                Bitmap result=new Bitmap(Math.Max(1,(int)(crop.Width*scale))+28,Math.Max(1,(int)(crop.Height*scale))+28);
                using(Graphics g=Graphics.FromImage(result)) {
                    g.Clear(Color.White);g.InterpolationMode=System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                    g.DrawImage(crop,14,14,result.Width-28,result.Height-28);
                }
                return result;
            }
        }
    }

    public interface IRightInput {
        bool CanArm(out string title);
        bool StillTarget();
        bool EscapePressed();
        bool ModifiersPressed();
        bool SendRight();
    }
    public sealed class RightCadence {
        long next=200;
        public bool Due(long elapsedMilliseconds) {
            if(elapsedMilliseconds<next)return false;
            next=(elapsedMilliseconds/200+1)*200;
            return true;
        }
    }
    public sealed class WindowsRightInput : IRightInput {
        [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT {public int dx,dy;public uint data,flags,time;public UIntPtr extra;}
        [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT {public ushort vk,scan;public uint flags,time;public UIntPtr extra;}
        [StructLayout(LayoutKind.Explicit)] struct INPUTUNION {[FieldOffset(0)]public MOUSEINPUT mouse;[FieldOffset(0)]public KEYBDINPUT key;}
        [StructLayout(LayoutKind.Sequential)] struct INPUT {public uint type;public INPUTUNION data;}
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint processId);
        [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr window);
        [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
        [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr window,System.Text.StringBuilder text,int count);
        [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr window,System.Text.StringBuilder text,int count);
        [DllImport("user32.dll",SetLastError=true)] static extern uint SendInput(uint count,INPUT[] inputs,int size);
        [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(Point point);
        [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr window,uint flags);
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
        [DllImport("user32.dll")] static extern bool SetCursorPos(int x,int y);
        IntPtr expected;IntPtr target;uint targetPid;
        public void ClearSelection(){expected=IntPtr.Zero;}
        public bool SelectPoint(Point point){
            IntPtr child=WindowFromPoint(point);IntPtr root=GetAncestor(child,2);uint pid;
            if(root==IntPtr.Zero||!IsWindowVisible(root)||IsIconic(root))return false;
            GetWindowThreadProcessId(root,out pid);
            if(pid==(uint)System.Diagnostics.Process.GetCurrentProcess().Id)return false;
            var cls=new System.Text.StringBuilder(256);GetClassName(root,cls,256);
            if(cls.ToString()=="Progman"||cls.ToString()=="WorkerW"||cls.ToString()=="Shell_TrayWnd")return false;
            if(!SetForegroundWindow(root)&&GetForegroundWindow()!=root)return false;
            if(!SetCursorPos(point.X,point.Y))return false;
            INPUT down=new INPUT();down.type=0;down.data.mouse.flags=2;
            INPUT up=down;up.data.mouse.flags=4;
            if(GetForegroundWindow()!=root||EscapePressed()||ModifiersPressed())return false;
            uint clicked=SendInput(2,new INPUT[]{down,up},InputSize);
            if(clicked!=2){if(clicked==1)SendInput(1,new INPUT[]{up},InputSize);return false;}
            expected=root;return true;
        }
        public static int InputSize {get{return Marshal.SizeOf(typeof(INPUT));}}
        public bool CanArm(out string title) {
            title="";target=GetForegroundWindow();
            if(expected!=IntPtr.Zero && target!=expected)return false;
            if(target==IntPtr.Zero||!IsWindowVisible(target)||IsIconic(target))return false;
            GetWindowThreadProcessId(target,out targetPid);
            if(targetPid==(uint)System.Diagnostics.Process.GetCurrentProcess().Id)return false;
            var name=new System.Text.StringBuilder(256);GetClassName(target,name,256);
            if(name.ToString()=="Progman"||name.ToString()=="WorkerW"||name.ToString()=="Shell_TrayWnd")return false;
            var text=new System.Text.StringBuilder(256);GetWindowText(target,text,256);title=text.ToString();
            return true;
        }
        public bool StillTarget() {
            uint pid;
            return target!=IntPtr.Zero&&IsWindow(target)&&IsWindowVisible(target)&&!IsIconic(target)&&GetForegroundWindow()==target&&GetWindowThreadProcessId(target,out pid)!=0&&pid==targetPid;
        }
        public bool EscapePressed(){return (GetAsyncKeyState(27)&0x8000)!=0;}
        public bool ModifiersPressed(){foreach(int k in new int[]{16,17,18,91,92})if((GetAsyncKeyState(k)&0x8000)!=0)return true;return false;}
        public bool SendRight() {
            if(!StillTarget()||EscapePressed()||ModifiersPressed())return false;
            INPUT down=new INPUT();down.type=1;down.data.key.vk=0x27;down.data.key.scan=0x4d;down.data.key.flags=1;
            INPUT up=down;up.data.key.flags=3;
            uint sent=SendInput(2,new INPUT[]{down,up},InputSize);
            if(sent==1)SendInput(1,new INPUT[]{up},InputSize);
            return sent==2;
        }
    }
    public sealed class AutoRightPump : IDisposable {
        readonly IRightInput input;readonly object gate=new object();
        System.Threading.ManualResetEvent cancel=new System.Threading.ManualResetEvent(false);
        System.Threading.Thread thread;
        volatile bool running,armed;string reason="",title="";long sent;
        public bool Running{get{return running;}} public bool Armed{get{return armed;}}
        public string StopReason{get{lock(gate)return reason;}}public string TargetTitle{get{lock(gate)return title;}}
        public long SentCount{get{return System.Threading.Interlocked.Read(ref sent);}}
        public AutoRightPump(IRightInput adapter){input=adapter;}
        public void Start(int delayMilliseconds){
            Stop();lock(gate){reason="";title="";sent=0;armed=false;cancel.Reset();running=true;}
            thread=new System.Threading.Thread(delegate(){Run(delayMilliseconds);});thread.IsBackground=true;thread.Name="NineMonitor.RightArrow";thread.Start();
        }
        void End(string why){lock(gate){reason=why;running=false;armed=false;}cancel.Set();}
        void Run(int delay){
            try{
                if(cancel.WaitOne(delay))return;
                string selected;if(!input.CanArm(out selected)){End("target");return;}
                lock(gate){title=selected;armed=true;}
                var clock=System.Diagnostics.Stopwatch.StartNew();var schedule=new RightCadence();
                while(!cancel.WaitOne(5)){
                    lock(gate){
                        if(cancel.WaitOne(0))break;
                        if(input.EscapePressed()){End("escape");return;}
                        if(!input.StillTarget()){End("focus");return;}
                        if(input.ModifiersPressed()){End("modifier");return;}
                        if(schedule.Due(clock.ElapsedMilliseconds)){
                            if(!input.SendRight()){End("input");return;}
                            System.Threading.Interlocked.Increment(ref sent);
                        }
                    }
                }
            }catch{End("error");}finally{running=false;armed=false;}
        }
        public void Stop(){lock(gate){cancel.Set();running=false;armed=false;}if(thread!=null&&thread!=System.Threading.Thread.CurrentThread){thread.Join();thread=null;}}
        public void Dispose(){Stop();cancel.Dispose();}
    }

    public sealed class TargetClickPicker : Form {
        Bitmap shot;
        public Point SelectedPoint {get;private set;}
        public TargetClickPicker(){
            Rectangle desk=SystemInformation.VirtualScreen;shot=Desktop.Capture(desk);
            FormBorderStyle=FormBorderStyle.None;StartPosition=FormStartPosition.Manual;Bounds=desk;
            TopMost=true;ShowInTaskbar=false;Cursor=Cursors.Cross;KeyPreview=true;DoubleBuffered=true;
        }
        protected override void OnPaint(PaintEventArgs e){
            e.Graphics.DrawImageUnscaled(shot,0,0);
            using(Brush b=new SolidBrush(Color.FromArgb(210,0,0,0)))e.Graphics.FillRectangle(b,0,Height-65,Width,65);
            using(Font f=new Font("Microsoft JhengHei",16,FontStyle.Bold))e.Graphics.DrawString("點選圖表的起始位置，開始搜索低點；ESC 取消",f,Brushes.White,24,Height-50);
        }
        protected override void OnMouseUp(MouseEventArgs e){
            if(e.Button!=MouseButtons.Left||e.Y>=Height-65)return;
            SelectedPoint=PointToScreen(e.Location);Hide();DialogResult=DialogResult.OK;Close();
        }
        protected override void OnKeyDown(KeyEventArgs e){if(e.KeyCode==Keys.Escape){DialogResult=DialogResult.Cancel;Close();}}
        protected override void Dispose(bool disposing){if(disposing&&shot!=null){shot.Dispose();shot=null;}base.Dispose(disposing);}
    }
}
