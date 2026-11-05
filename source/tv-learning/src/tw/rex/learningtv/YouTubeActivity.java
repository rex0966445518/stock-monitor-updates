package tw.rex.learningtv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;

/** In-app selection and official embedded playback; no external application required. */
public class YouTubeActivity extends Activity {
 static YouTubeActivity active;
 WebView web; LinearLayout root; TextView message; boolean embedded, covered, resumePending, ready, foreground;
 String video="", selection="https://m.youtube.com/";
 final Handler handler=new Handler();
 android.content.SharedPreferences control;
 final Runnable guard=new Runnable(){public void run(){if(web==null)return;TvService service=TvService.instance;
 boolean block=control.getBoolean("locked",false)||(service!=null&&service.watchPhase()==tw.rex.link.WatchRules.Phase.QUESTION);
 if(block)cover();else uncover();handler.postDelayed(this,100);}};
 static boolean exists(){return active!=null&&active.web!=null;}
 static void pauseInternal(){if(exists())active.cover();}
 static boolean resumeInternal(){if(!exists())return false;if(active.foreground)active.uncover();return true;}
 @Override public void onCreate(Bundle state){super.onCreate(state);active=this;control=getSharedPreferences("control",0);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
 root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xff111111);setContentView(root);
 LinearLayout bar=new LinearLayout(this);root.addView(bar);button(bar,"返回選片",()->browse(selection));button(bar,"輸入影片連結",()->input());button(bar,"重新載入",()->{if(!blocked())web.reload();});button(bar,"返回學習島",()->onBackPressed());
 message=new TextView(this);message.setTextColor(-1);message.setTextSize(16);message.setPadding(16,6,16,6);root.addView(message);
 try{web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setMediaPlaybackRequiresUserGesture(true);settings.setSupportMultipleWindows(false);
 web.setWebChromeClient(new WebChromeClient());web.setWebViewClient(new WebViewClient(){
 public boolean shouldOverrideUrlLoading(WebView v,String url){return navigate(url);}
 public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return r.isForMainFrame()&&navigate(r.getUrl().toString());}
 public void onPageStarted(WebView v,String url,Bitmap icon){ready=false;}
 public void onPageFinished(WebView v,String url){ready=true;if(!embedded)v.evaluateJavascript("document.addEventListener('click',function(e){var a=e.target.closest('a');if(a&&/\\/(watch|shorts\\/)/.test(a.href)){e.preventDefault();e.stopImmediatePropagation();location.href=a.href;}},true)",null);if(blocked())cover();}
 public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())message.setText("網頁載入失敗，請確認電視網路後按重新載入。無需安裝 YouTube App。");}
 public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler h,android.net.http.SslError e){h.cancel();message.setText("連線憑證驗證失敗，請確認電視日期與系統更新。");}
 });String q=getIntent().getStringExtra("query");if(q!=null&&!q.isEmpty()){String id=videoId(q);if(id!=null)play(id);else browse("https://m.youtube.com/results?search_query="+Uri.encode(q));}else browse(selection);
 }catch(RuntimeException e){web=null;message.setText("這台電視的系統 WebView 無法啟動。請在系統設定更新 Android System WebView／系統元件後重試；不需要安裝 YouTube。");}
 }
 void button(LinearLayout bar,String title,Runnable run){Button b=new Button(this);b.setText(title);bar.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{if(blocked()){Toast.makeText(this,"請先由家長結束控制",0).show();return;}if(web!=null)run.run();});}
 boolean blocked(){TvService s=TvService.instance;return control.getBoolean("locked",false)||(s!=null&&s.watchPhase()==tw.rex.link.WatchRules.Phase.QUESTION);}
 static boolean youtube(Uri u){String h=u.getHost();return "youtube.com".equals(h)||"www.youtube.com".equals(h)||"m.youtube.com".equals(h)||"youtu.be".equals(h);}
 static String videoId(String url){return tw.rex.link.YouTubeLink.videoId(url);}

 boolean navigate(String url){if(blocked())return true;Uri u=Uri.parse(url);String id=videoId(url);if(id!=null){play(id);return true;}if("https".equals(u.getScheme())&&youtube(u)){embedded=false;selection=url;return false;}message.setText("請在內建 YouTube 選片，或輸入 YouTube 影片連結。");return true;}
 void browse(String url){if(blocked())return;embedded=false;video="";selection=url;message.setText("內建 YouTube · 使用方向鍵選片／搜尋；也可輸入影片連結。無需另外安裝 App。");web.loadUrl(url);}
 void input(){EditText in=new EditText(this);in.setSingleLine(true);in.setHint("https://www.youtube.com/watch?v=…");new AlertDialog.Builder(this).setTitle("開啟 YouTube 影片").setView(in).setPositiveButton("播放",(d,w)->{if(blocked())return;String id=videoId(in.getText().toString().trim());if(id==null)Toast.makeText(this,"請輸入有效的 YouTube 影片連結",1).show();else play(id);}).setNegativeButton("取消",null).show();}
 void play(String id){if(blocked())return;video=id;embedded=true;resumePending=false;message.setText("內建播放 · 按影片播放鍵開始；家長可在手機啟動邊看邊學。禁止嵌入的影片請返回重新選片。");String base="https://"+getPackageName()+"/";
 String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body,#player{margin:0;width:100%;height:100%;background:#000;color:white}#error{font:22px sans-serif}</style></head><body><div id='player'></div><div id='error'></div><script>var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'"+id+"',playerVars:{playsinline:1,origin:'https://"+getPackageName()+"',fs:0},events:{onError:function(e){document.getElementById('error').textContent='此影片無法內嵌播放（'+e.data+'），請返回選片。';}}});}function suspendVideo(){var s=player&&player.getPlayerState?player.getPlayerState():-1;if(player&&player.pauseVideo)player.pauseVideo();return s===1||s===3;}function resumeVideo(){if(player&&player.playVideo)player.playVideo();}</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
 web.loadDataWithBaseURL(base,html,"text/html","UTF-8",null);
 }
 void cover(){if(web==null||covered)return;covered=true;
 if(embedded)web.evaluateJavascript("suspendVideo()",value->{resumePending="true".equals(value);});else web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})",null);
 web.onPause();web.setVisibility(View.GONE);message.setText("影片已暫停，請完成答題或由家長解除鎖定。");}
 void uncover(){if(web==null||!covered||blocked())return;covered=false;web.setVisibility(View.VISIBLE);web.onResume();message.setText("內建 YouTube · 答對後接續原影片。若系統阻擋自動播放，請按影片播放鍵。");if(embedded&&resumePending){resumePending=false;web.evaluateJavascript("resumeVideo()",null);}}
 @Override protected void onResume(){super.onResume();foreground=true;active=this;if(web!=null){web.onResume();handler.removeCallbacks(guard);handler.post(guard);}}
 @Override protected void onPause(){foreground=false;if(web!=null)cover();handler.removeCallbacks(guard);super.onPause();}
 @Override public void onBackPressed(){if(control.getBoolean("watchEnabled",false)||control.getBoolean("locked",false)||control.getBoolean("strict",false)){Toast.makeText(this,"請由家長手機結束模式後返回",1).show();return;}if(embedded){browse(selection);return;}super.onBackPressed();}
 @Override public boolean dispatchKeyEvent(KeyEvent e){if((control.getBoolean("watchEnabled",false)||control.getBoolean("locked",false))&&CuteGuard.blocked(e.getKeyCode())){if(e.getAction()==KeyEvent.ACTION_DOWN)Toast.makeText(this,"請由家長手機結束模式",0).show();return true;}return super.dispatchKeyEvent(e);}
 @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(active==this)active=null;if(web!=null){root.removeView(web);web.destroy();web=null;}super.onDestroy();}
}
