package com.ai.dramasearch;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.Toast;
import java.io.IOException;
import java.util.Collections;
public class MainActivity extends Activity {
 private static final String HOST="appassets.androidplatform.net";
 private static final String HOME="https://"+HOST+"/index.html";
 private WebView view;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state); view=new WebView(this); view.setBackgroundColor(Color.rgb(16,16,26)); setContentView(view);
  WebSettings s=view.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportMultipleWindows(false); s.setMediaPlaybackRequiresUserGesture(true); s.setSafeBrowsingEnabled(true);
  view.setWebChromeClient(new WebChromeClient());
  view.setWebViewClient(new WebViewClient(){
   @Override public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest r){
    Uri u=r.getUrl(); if(!"https".equals(u.getScheme())||!HOST.equals(u.getHost()))return null;
    if(!"/index.html".equals(u.getPath()))return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),null);
    try{return new WebResourceResponse("text/html","UTF-8",getAssets().open("index.html"));}catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),null);}
   }
   @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){
    Uri u=r.getUrl(); if(!r.isForMainFrame())return !"https".equals(u.getScheme()); if(HOME.equals(u.toString()))return false;
    if("https".equals(u.getScheme())&&u.getHost()!=null&&!HOST.equals(u.getHost())){try{startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception e){Toast.makeText(MainActivity.this,"找不到可開啟影片的瀏覽器",Toast.LENGTH_LONG).show();}} return true;
   }
  });
  if(state==null||view.restoreState(state)==null)view.loadUrl(HOME);
 }
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);view.saveState(out);}
 @Override public void onBackPressed(){if(view.canGoBack())view.goBack();else super.onBackPressed();}
 @Override protected void onPause(){super.onPause();view.evaluateJavascript("window.savePlayback && window.savePlayback()",null);view.onPause();}
 @Override protected void onResume(){super.onResume();view.onResume();}
 @Override protected void onDestroy(){view.destroy();super.onDestroy();}
}
