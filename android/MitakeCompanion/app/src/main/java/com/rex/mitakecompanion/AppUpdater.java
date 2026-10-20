package com.rex.mitakecompanion;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** DownloadManager keeps a user-requested download alive across activity restarts. */
public final class AppUpdater implements AutoCloseable {
    final Activity activity;final SharedPreferences prefs;
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ProgressDialog progress;
    private boolean busy,visible,closed;
    private final Runnable poll=new Runnable(){public void run(){checkDownload();}};
    AppUpdater(Activity a){activity=a;prefs=a.getSharedPreferences("updates",Context.MODE_PRIVATE);
        try{if(current().equals(prefs.getString("version","")))prefs.edit().clear().apply();}catch(Exception ignored){}
    }
    private void message(String title,String value){if(!closed&&visible)new AlertDialog.Builder(activity).setTitle(title).setMessage(value).setPositiveButton("確定",null).show();}
    private String current()throws Exception{return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;}
    static String get(String address,int limit)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","MitakeCompanion-Updater");
        try{if(c.getResponseCode()!=200)throw new IOException("更新伺服器回應 "+c.getResponseCode());try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("更新資訊過大");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());}}finally{c.disconnect();}
    }
    void check(){
        if(busy)return;
        if(prefs.getBoolean("ready",false)){install();return;}
        if(prefs.getLong("download_id",-1)!=-1){showProgress("繼續下載更新");checkDownload();return;}
        busy=true;showProgress("檢查手機版更新…");
        worker.execute(()->{
            try{
                UpdatePolicy.Release r=UpdatePolicy.newest(new JSONArray(get(UpdatePolicy.API,2*1024*1024)),current());
                String digest=r==null?"":!r.digest.isEmpty()?r.digest:UpdatePolicy.checksum(get(r.checksumUrl,8192),"MitakeCompanion-v"+r.version+".apk");
                main.post(()->{busy=false;dismiss();if(closed)return;if(r==null)message("已是最新版本","目前手機版 v"+versionLabel());else new AlertDialog.Builder(activity).setTitle("更新至 v"+r.version).setMessage("下載約 "+String.format(Locale.TAIWAN,"%.1f MB",r.size/1048576.0)+"，安裝後保留配對及語音設定。Android 會要求你確認安裝。").setPositiveButton("下載更新",(d,w)->download(r,digest)).setNegativeButton("取消",null).show();});
            }catch(Exception e){main.post(()->{busy=false;dismiss();message("無法檢查更新",safe(e));});}
        });
    }
    private String versionLabel(){try{return current();}catch(Exception e){return "未知";}}
    private void download(UpdatePolicy.Release r,String digest){
        try{
            String name="MitakeCompanion-v"+r.version+".apk";
            File root=activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(root==null)throw new IOException("下載空間不可用");
            File dir=new File(root,"updates");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("無法建立更新資料夾");
            File file=new File(dir,name);if(file.exists()&&!file.delete())throw new IOException("無法取代舊更新檔");
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(r.url)).setTitle("漲停雷達 v"+r.version).setDescription("手機版更新").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationUri(Uri.fromFile(file));
            long id=activity.getSystemService(DownloadManager.class).enqueue(req);
            prefs.edit().putLong("download_id",id).putString("file",file.getAbsolutePath()).putString("sha256",digest).putString("version",r.version).putLong("size",r.size).putBoolean("ready",false).apply();
            showProgress("下載 v"+r.version);checkDownload();
        }catch(Exception e){dismiss();message("下載失敗",safe(e));}
    }
    void resume(){visible=true;if(prefs.getBoolean("install_pending",false)&&activity.getPackageManager().canRequestPackageInstalls()){prefs.edit().putBoolean("install_pending",false).apply();install();}else if(prefs.getLong("download_id",-1)!=-1){showProgress("繼續下載更新");checkDownload();}}
    void pause(){visible=false;main.removeCallbacks(poll);dismiss();}
    private void checkDownload(){
        main.removeCallbacks(poll);long id=prefs.getLong("download_id",-1);if(id==-1||closed||!visible)return;
        try(Cursor c=activity.getSystemService(DownloadManager.class).query(new DownloadManager.Query().setFilterById(id))){
            if(c==null||!c.moveToFirst())throw new IOException("下載紀錄已移除，請重新更新");
            int state=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if(state==DownloadManager.STATUS_FAILED)throw new IOException("下載失敗，錯誤碼 "+c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
            if(state==DownloadManager.STATUS_SUCCESSFUL){if(busy)return;busy=true;showProgress("驗證更新檔…");worker.execute(()->{try{verify();main.post(()->{busy=false;prefs.edit().remove("download_id").putBoolean("ready",true).apply();dismiss();if(visible&&!closed)new AlertDialog.Builder(activity).setTitle("更新已下載並驗證").setMessage("v"+prefs.getString("version","")+" 已就緒。").setPositiveButton("安裝更新",(d,w)->install()).setNegativeButton("稍後",null).show();});}catch(Exception e){main.post(()->fail(e));}});return;}
            long done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if(progress!=null){progress.setIndeterminate(total<=0);if(total>0){progress.setMax(100);progress.setProgress((int)(100*done/total));}progress.setMessage(state==DownloadManager.STATUS_PAUSED?"等待網路，恢復後自動繼續":"下載手機更新中");}
            main.postDelayed(poll,500);
        }catch(Exception e){fail(e);}
    }
    private void fail(Exception e){busy=false;prefs.edit().remove("download_id").putBoolean("ready",false).apply();dismiss();message("更新失敗",safe(e));}
    private static String safe(Exception e){return e.getMessage()==null?"請檢查網路或稍後重試":e.getMessage();}
    private void verify()throws Exception{
        File f=new File(prefs.getString("file",""));if(!f.isFile()||f.length()!=prefs.getLong("size",-1))throw new IOException("更新檔大小不符，請重新下載");
        MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1)md.update(buf,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:md.digest())hex.append(String.format(Locale.US,"%02x",b&255));
        if(!hex.toString().equals(prefs.getString("sha256","")))throw new IOException("更新檔校驗失敗，請重新下載");
        PackageManager pm=activity.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo installed=pm.getPackageInfo(activity.getPackageName(),flags),archive=pm.getPackageArchiveInfo(f.getAbsolutePath(),flags);
        if(archive==null||!installed.packageName.equals(archive.packageName)||!prefs.getString("version","").equals(archive.versionName))throw new IOException("不是正確的漲停雷達更新檔");
        long oldCode=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode,newCode=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
        if(newCode<=oldCode)throw new IOException("此更新版本不高於目前版本，請重新檢查更新");
        Signature[] a=Build.VERSION.SDK_INT>=28?installed.signingInfo.getApkContentsSigners():installed.signatures,b=Build.VERSION.SDK_INT>=28?archive.signingInfo.getApkContentsSigners():archive.signatures;
        if(a==null||b==null||a.length!=1||b.length!=1||!a[0].equals(b[0]))throw new IOException("更新簽章不一致，已停止安裝");
    }
    private void install(){
        worker.execute(()->{try{verify();main.post(()->{
            if(closed||!visible)return;
            try{
                if(!activity.getPackageManager().canRequestPackageInstalls()){
                    prefs.edit().putBoolean("install_pending",true).apply();
                    message("允許安裝更新","請在接下來的系統頁面允許此 App 安裝更新，返回後會開啟安裝確認。");
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));return;
                }
                Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".updates",new File(prefs.getString("file","")));
                activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }catch(RuntimeException e){message("無法開啟安裝",safe(e));}
        });}catch(Exception e){main.post(()->{prefs.edit().putBoolean("ready",false).remove("download_id").apply();message("更新檔不可用",safe(e));});}});
    }
    private void showProgress(String label){if(closed||!visible)return;if(progress==null){progress=new ProgressDialog(activity);progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);progress.setTitle("一鍵更新");progress.setCancelable(true);progress.setOnCancelListener(d->{progress=null;});}progress.setMessage(label);progress.setIndeterminate(true);progress.show();}
    private void dismiss(){if(progress!=null){progress.dismiss();progress=null;}}
    @Override public void close(){closed=true;visible=false;main.removeCallbacksAndMessages(null);dismiss();worker.shutdownNow();}
}
