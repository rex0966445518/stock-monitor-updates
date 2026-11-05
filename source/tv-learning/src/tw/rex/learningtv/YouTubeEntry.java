package tw.rex.learningtv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;

/** Opens the installed player without replacing the existing watch/quiz service. */
final class YouTubeEntry {
 static final String[] PACKAGES={"com.google.android.youtube.tv","com.google.android.youtube"};
 static boolean allowed(MainActivity a){
  if(a.childGuard()){Toast.makeText(a,"請先由家長結束目前控制，再選擇 YouTube 影片",Toast.LENGTH_LONG).show();return false;}
  return true;
 }
 static void show(MainActivity a){
  if(!allowed(a))return;
  LinearLayout content=new LinearLayout(a);content.setOrientation(1);content.setPadding(a.dp(24),a.dp(8),a.dp(24),a.dp(8));
  TextView help=a.text("輸入關鍵字搜尋，或直接開啟內建 YouTube 選片（無需另外安裝）。\n\n邊看邊學：先播放影片，再由家長手機選題目與觀看秒數，按「開始邊看邊學」。答對後收起題目，繼續原影片，時間到再出題。\n嚴格模式請在家長端選擇「YouTube（內建免安裝）」。",17,tw.rex.link.Skin.ink(a));content.addView(help);
  EditText query=new EditText(a);query.setSingleLine(true);query.setHint("搜尋 YouTube，例如：兒童英文");query.setInputType(InputType.TYPE_CLASS_TEXT);content.addView(query);
  Button permission=tw.rex.link.Skin.button(a,Settings.canDrawOverlays(a)?"上層顯示已授權 · 家長連線設定":"設定邊看邊學的上層顯示權限",1);content.addView(permission);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("▶ YouTube 搜尋與觀看").setView(content).setPositiveButton("搜尋",null).setNeutralButton("開啟 YouTube",null).setNegativeButton("返回",null).create();
  permission.setOnClickListener(v->{if(!allowed(a))return;dialog.dismiss();if(Settings.canDrawOverlays(a))a.parentPanel();else try{a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+a.getPackageName())));}catch(RuntimeException e){a.parentPanel();}});
  dialog.setOnShowListener(v->{dialog.getButton(-1).setOnClickListener(w->{String q=query.getText().toString().trim();if(q.isEmpty()){query.setError("請輸入搜尋關鍵字，或選擇開啟 YouTube");query.requestFocus();return;}if(open(a,q))dialog.dismiss();});dialog.getButton(-3).setOnClickListener(w->{if(open(a,""))dialog.dismiss();});dialog.getButton(-3).requestFocus();});dialog.show();
 }
 static boolean launch(MainActivity a,Intent intent){try{intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);a.startActivity(intent);return true;}catch(ActivityNotFoundException|SecurityException e){return false;}}
 static boolean open(MainActivity a,String query){
  if(!allowed(a))return false;
  a.startActivity(new Intent(a,YouTubeActivity.class).putExtra("query",query));return true;
 }
}
