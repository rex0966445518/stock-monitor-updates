$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::SetUnhandledExceptionMode([Windows.Forms.UnhandledExceptionMode]::ThrowException)
$tests=@'
namespace NineMonitor.Tests {
    public static class SelectorSuite {
        static System.Reflection.BindingFlags Flags=System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Instance;
        static void Check(bool value,string message){if(!value)throw new System.Exception(message);}
        static void Mouse(NineMonitor.Selector form,string name,int x,int y){
            var e=new System.Windows.Forms.MouseEventArgs(System.Windows.Forms.MouseButtons.Left,1,x,y,0);
            form.GetType().GetMethod(name,Flags).Invoke(form,new object[]{e});
        }
        static void Key(NineMonitor.Selector form,System.Windows.Forms.Keys key){
            form.GetType().GetMethod("OnKeyDown",Flags).Invoke(form,new object[]{new System.Windows.Forms.KeyEventArgs(key)});
        }
        static System.Drawing.Rectangle RunCase(int which){
            using(var image=new System.Drawing.Bitmap(400,250))
            using(var form=new NineMonitor.Selector(image,new System.Drawing.Rectangle(0,0,400,250)))
            using(var watchdog=new System.Windows.Forms.Timer()){
                string failure=null;watchdog.Interval=2500;
                watchdog.Tick+=delegate{failure="Selection dialog did not finish: case "+which;form.DialogResult=System.Windows.Forms.DialogResult.Abort;form.Close();};
                form.Shown+=delegate{
                    watchdog.Start();
                    try{
                        if(which==0){Mouse(form,"OnMouseDown",50,100);Mouse(form,"OnMouseMove",94,111);Mouse(form,"OnMouseUp",97,113);}
                        if(which==1){Mouse(form,"OnMouseDown",30,90);Mouse(form,"OnMouseUp",32,92);Check(form.DialogResult!=System.Windows.Forms.DialogResult.OK,"Accidental click was accepted");Mouse(form,"OnMouseDown",120,110);Mouse(form,"OnMouseUp",70,100);}
                        if(which==2){
                            Mouse(form,"OnMouseDown",30,90);Mouse(form,"OnMouseMove",75,102);
                            System.Windows.Forms.Cursor.Position=form.PointToScreen(new System.Drawing.Point(78,104));
                            var timer=(System.Windows.Forms.Timer)form.GetType().GetField("releaseWatch",Flags).GetValue(form);
                            timer.GetType().GetMethod("OnTick",Flags).Invoke(timer,new object[]{System.EventArgs.Empty});
                        }
                        if(which==3){Mouse(form,"OnMouseDown",40,80);Mouse(form,"OnMouseMove",92,94);System.Windows.Forms.Cursor.Position=form.PointToScreen(new System.Drawing.Point(92,94));Key(form,System.Windows.Forms.Keys.Enter);}
                        if(which==4){Mouse(form,"OnMouseDown",40,80);Key(form,System.Windows.Forms.Keys.Escape);}
                    }catch(System.Exception e){failure=e.ToString();form.DialogResult=System.Windows.Forms.DialogResult.Abort;form.Close();}
                };
                var result=form.ShowDialog();watchdog.Stop();
                if(failure!=null)throw new System.Exception(failure);
                Check(result==(which==4?System.Windows.Forms.DialogResult.Cancel:System.Windows.Forms.DialogResult.OK),"Incorrect dialog result in case "+which);
                return form.Selected;
            }
        }
        public static string Run(){
            var size=new System.Drawing.Size(400,250);
            var r=NineMonitor.SelectionGeometry.Build(new System.Drawing.Point(90,80),new System.Drawing.Point(45,68),size);
            Check(r.Width==45&&r.Height==12&&NineMonitor.SelectionGeometry.Valid(r),"Small reverse drag rejected");
            r=NineMonitor.SelectionGeometry.Build(new System.Drawing.Point(3,2),new System.Drawing.Point(-20,-20),size);
            Check(r.X==0&&r.Y==0&&!NineMonitor.SelectionGeometry.Valid(r),"Invalid screen-edge click accepted");
            r=RunCase(0);Check(r.Width==47&&r.Height==13,"Release endpoint was not used");
            r=RunCase(1);Check(r.Width==50&&r.Height==10,"Redraw after tiny selection failed");
            r=RunCase(2);Check(r.Width==48&&r.Height==14,"Mouse-release watchdog did not use current cursor");
            r=RunCase(3);Check(r.Width==52&&r.Height==14,"Enter did not confirm current selection");
            r=RunCase(4);Check(r.IsEmpty,"ESC saved a region");
            return "Selector small-height, reverse-drag, release, watchdog, Enter and ESC regression tests passed";
        }
    }
}
'@
Add-Type -TypeDefinition ((Get-Content (Join-Path $root 'Native.cs') -Raw -Encoding UTF8)+$tests) -ReferencedAssemblies 'System.Drawing.dll','System.Windows.Forms.dll'
Write-Host ([NineMonitor.Tests.SelectorSuite]::Run())
