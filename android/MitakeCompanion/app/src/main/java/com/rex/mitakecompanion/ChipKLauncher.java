package com.rex.mitakecompanion;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.widget.Toast;

/** Explicit user-triggered handoff to CMoney's published Android package. */
public final class ChipKLauncher {
    static final String PACKAGE="com.cmoney.chipk";
    static final String STORE="https://play.google.com/store/apps/details?id="+PACKAGE;
    static Intent appIntent(android.content.pm.PackageManager pm){return pm.getLaunchIntentForPackage(PACKAGE);}
    static void copy(Activity a,String code){
        if(!IntradayData.validCode(code)){new AlertDialog.Builder(a).setMessage("股號待確認，無法複製").setPositiveButton("確定",null).show();return;}
        a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("股票代號",code));
        Toast.makeText(a,"已複製股號 "+code,Toast.LENGTH_SHORT).show();
    }
    static void open(Activity a,String code){
        try{Intent intent=appIntent(a.getPackageManager());if(intent!=null){a.startActivity(intent);return;}}
        catch(ActivityNotFoundException | SecurityException ignored){}
        new AlertDialog.Builder(a).setTitle("無法開啟籌碼K線")
            .setMessage("尚未安裝、已停用或無法啟動籌碼K線 App。\n\n股號："+code+"\n安裝後可再次點擊卡片測試。此版開啟 App 主頁，未指定個股頁。")
            .setPositiveButton("前往安裝",(d,w)->{try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(STORE)));}catch(RuntimeException e){Toast.makeText(a,"請到 Google Play 搜尋籌碼K線",Toast.LENGTH_LONG).show();}})
            .setNeutralButton("複製股號",(d,w)->copy(a,code)).setNegativeButton("取消",null).show();
    }
}
