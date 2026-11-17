package tw.rex.nextmarket;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import org.json.JSONObject;

public class Main extends Activity {
 private static final String ROOT="https://raw.githubusercontent.com/rex0966445518/stock-monitor-updates/main/taiwanforecast/";
 private WebView web;
 private byte[] poster;
 private boolean busy;
 private int wanted;
 private String digest;
 private void status(final String message){runOnUiThread(()->{if(!isFinishing())web.evaluateJavascript("window.updateStatus("+JSONObject.quote(message)+")",null);});}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowUniversalAccessFromFileURLs(true);
  web.addJavascriptInterface(this,"Poster");setContentView(web);web.loadUrl("file:///android_asset/index.html");
 }
 @JavascriptInterface public void checkUpdate(final boolean manual){runOnUiThread(()->{
  if(busy)return;busy=true;if(manual)status("正在檢查 GitHub 版本…");
  new Thread(()->{try{
   JSONObject m;try(InputStream input=open(ROOT+"latest.json?t="+System.currentTimeMillis());ByteArrayOutputStream out=new ByteArrayOutputStream()){copy(input,out,8192);m=new JSONObject(out.toString("UTF-8"));}
   int code=m.getInt("versionCode");String hash=m.getString("sha256");
   if(!getPackageName().equals(m.getString("package"))||!hash.matches("[a-f0-9]{64}"))throw new IOException("更新資訊不符");
   int current=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
   runOnUiThread(()->{busy=false;if(isFinishing())return;
    if(code<=current){if(manual)status("已是最新版本");return;}
    wanted=code;digest=hash;status("有新版可更新");
    if(manual)installAllowed();else new AlertDialog.Builder(this).setTitle("台股隔日預測神器有新版").setMessage("版本 "+m.optString("versionName")).setPositiveButton("立即更新",(d,w)->installAllowed()).setNegativeButton("稍後",null).show();
   });
  }catch(Exception e){runOnUiThread(()->{busy=false;if(manual)status("更新檢查失敗："+e.getMessage());});}}).start();
 });}
 private static InputStream open(String url)throws IOException{
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(25000);c.setUseCaches(false);
  return c.getInputStream();
 }
 private static void copy(InputStream in,OutputStream out,long limit)throws IOException{
  byte[] b=new byte[16384];int n;long size=0;while((n=in.read(b))!=-1){size+=n;if(size>limit)throw new IOException("更新檔過大");out.write(b,0,n);}
 }
 private void installAllowed(){
  if(busy||wanted==0)return;
  if(!getPackageManager().canRequestPackageInstalls()){
   status("請允許此 App 安裝更新，然後返回");
   startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),2);return;
  }
  busy=true;final int version=wanted;final String hash=digest;status("正在下載並驗證更新…");
  new Thread(()->{
   File file=new File(getCacheDir(),"forecast-update.apk");PackageInstaller pi=getPackageManager().getPackageInstaller();int id=-1;
   try{
    String tag="taiwanforecast-"+version;
    InputStream input;try{input=open("https://github.com/rex0966445518/stock-monitor-updates/releases/download/"+tag+"/TaiwanForecast.apk");}catch(IOException e){input=open(ROOT+version+"/TaiwanForecast.apk");}
    try(InputStream src=input;OutputStream dst=new FileOutputStream(file)){copy(src,dst,64L*1024*1024);}
    MessageDigest md=MessageDigest.getInstance("SHA-256");
    try(InputStream src=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=src.read(b))!=-1)md.update(b,0,n);}
    StringBuilder actual=new StringBuilder();for(byte v:md.digest())actual.append(String.format(java.util.Locale.US,"%02x",v&255));
    PackageInfo apk=getPackageManager().getPackageArchiveInfo(file.toString(),PackageManager.GET_SIGNATURES),installed=getPackageManager().getPackageInfo(getPackageName(),PackageManager.GET_SIGNATURES);
    if(!hash.contentEquals(actual)||apk==null||!getPackageName().equals(apk.packageName)||apk.versionCode!=version||version<=installed.versionCode||!java.util.Arrays.equals(installed.signatures,apk.signatures))throw new IOException("套件、版本或簽章驗證失敗");
    PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(getPackageName());id=pi.createSession(params);
    try(PackageInstaller.Session session=pi.openSession(id)){
     try(InputStream src=new FileInputStream(file);OutputStream dst=session.openWrite("base.apk",0,file.length())){copy(src,dst,file.length());session.fsync(dst);}
     Intent callback=new Intent(this,InstallResult.class);
     PendingIntent notify=PendingIntent.getBroadcast(this,id,callback,PendingIntent.FLAG_UPDATE_CURRENT|(android.os.Build.VERSION.SDK_INT>=31?0x02000000:0));
     session.commit(notify.getIntentSender());id=-1;status("請確認 Android 安裝提示");
    }
   }catch(Exception e){if(id>=0)pi.abandonSession(id);status("更新失敗："+e.getMessage());}finally{file.delete();runOnUiThread(()->busy=false);}
  }).start();
 }
 @JavascriptInterface public void savePoster(String data){
  if(data==null||!data.startsWith("data:image/png;base64,"))return;
  poster=Base64.decode(data.substring(22),Base64.DEFAULT);
  runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png").putExtra(Intent.EXTRA_TITLE,"Taiwan-Forecast.png"),1));
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);if(request==2){if(getPackageManager().canRequestPackageInstalls()){if(wanted>0)installAllowed();else checkUpdate(true);}else status("安裝權限尚未開啟");return;}
  if(request==1&&result==RESULT_OK&&data!=null&&poster!=null)try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(poster);}catch(Exception e){Toast.makeText(this,"海報儲存失敗",0).show();}
 }
}
