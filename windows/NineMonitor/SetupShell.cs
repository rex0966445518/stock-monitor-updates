using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
namespace NineMonitor {
    [ComImport,Guid("00021401-0000-0000-C000-000000000046")] class ShellLinkObject { }
    [ComImport,InterfaceType(ComInterfaceType.InterfaceIsIUnknown),Guid("000214F9-0000-0000-C000-000000000046")]
    interface IShellLinkW {
        void GetPath([Out,MarshalAs(UnmanagedType.LPWStr)] StringBuilder path,int count,IntPtr data,uint flags);
        void GetIDList(out IntPtr pidl);void SetIDList(IntPtr pidl);
        void GetDescription([Out,MarshalAs(UnmanagedType.LPWStr)] StringBuilder text,int count);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string text);
        void GetWorkingDirectory([Out,MarshalAs(UnmanagedType.LPWStr)] StringBuilder path,int count);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string path);
        void GetArguments([Out,MarshalAs(UnmanagedType.LPWStr)] StringBuilder text,int count);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string text);
        void GetHotkey(out short hotkey);void SetHotkey(short hotkey);
        void GetShowCmd(out int cmd);void SetShowCmd(int cmd);
        void GetIconLocation([Out,MarshalAs(UnmanagedType.LPWStr)] StringBuilder path,int count,out int index);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path,int index);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path,uint reserved);
        void Resolve(IntPtr hwnd,uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string path);
    }
    public static class SetupShell {
        public static void Create(string linkPath,string target,string workingDirectory) {
            object obj=new ShellLinkObject();
            try {
                IShellLinkW link=(IShellLinkW)obj;
                link.SetPath(target);link.SetWorkingDirectory(workingDirectory);
                link.SetDescription("三色數值監視器");link.SetShowCmd(1);
                ((IPersistFile)obj).Save(linkPath,true);
            } finally {Marshal.FinalReleaseComObject(obj);}
        }
    }
}
