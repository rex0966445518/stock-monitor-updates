package tw.rex.learningtv;
import android.app.*;import tw.rex.link.Skin;import android.content.*;import android.graphics.Color;import android.view.*;import android.widget.*;
final class CuteGuard {
 static AlertDialog dialog;
 static boolean blocked(int code){return code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_ESCAPE||code==KeyEvent.KEYCODE_HOME||code==KeyEvent.KEYCODE_APP_SWITCH||code==KeyEvent.KEYCODE_ALL_APPS||code==KeyEvent.KEYCODE_TV_INPUT||code==KeyEvent.KEYCODE_SETTINGS||code==KeyEvent.KEYCODE_GUIDE||code==KeyEvent.KEYCODE_MENU;}
 static void show(Context c){if(dialog!=null&&dialog.isShowing())return;try{LinearLayout row=new LinearLayout(c);row.setOrientation(0);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(20,16,20,16);row.setBackground(Skin.panel(c));int width=(int)(220*c.getResources().getDisplayMetrics().density);row.addView(Skin.mascot(c),new LinearLayout.LayoutParams(width,(int)(120*c.getResources().getDisplayMetrics().density)));TextView text=new TextView(c);text.setText("不能退出唷！\n需要喊媽媽輸入密碼才能解除，嘿嘿嘿！\n\n家長請在已配對的手機驗證後允許退出。");text.setTextSize(22);text.setTextColor(Skin.ink(c));text.setPadding(18,8,8,8);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));dialog=new AlertDialog.Builder(c).setTitle(Skin.name(c)+"提醒你").setView(row).setPositiveButton("知道了，繼續學習！",(d,w)->{}).setCancelable(false).create();if(!(c instanceof Activity)){dialog.getWindow().setType(android.os.Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE);}dialog.setOnKeyListener((d,key,event)->blocked(key));dialog.show();dialog.getWindow().setLayout((int)(c.getResources().getDisplayMetrics().widthPixels*.88),-2);}catch(Exception e){Toast.makeText(c,"不能退出唷！需要喊媽媽用手機解除，嘿嘿嘿！",Toast.LENGTH_LONG).show();}}
 static void close(){if(dialog!=null){try{dialog.dismiss();}catch(Exception e){}dialog=null;}}
 static class QuizScroll extends ScrollView {
  QuizScroll(Context c){super(c);}
  public boolean dispatchKeyEvent(KeyEvent event){int code=event.getKeyCode();boolean media=code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||code==KeyEvent.KEYCODE_MEDIA_NEXT||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS;if(blocked(code)||media){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)show(getContext());return true;}return super.dispatchKeyEvent(event);}
 }
}
