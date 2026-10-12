package com.rex.twboardingscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Only use the official stock URL; never invent undocumented third-party URI schemes. */
object ChipKLauncher {
    const val PACKAGE = "com.cmoney.chipk"
    fun stockIntent(code:String):Intent {
        require(code.matches(Regex("[0-9]{4,6}")))
        return Intent(Intent.ACTION_VIEW,Uri.parse("https://www.cmoney.tw/finance/$code/stockmainkline"))
            .setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun open(context:Context,code:String,name:String) {
        if(!code.matches(Regex("[0-9]{4,6}"))) {
            Toast.makeText(context,"股號格式不正確",Toast.LENGTH_SHORT).show();return
        }
        val stock=stockIntent(code)
        val launch=context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if(stock.resolveActivity(context.packageManager)==null&&launch==null){
            Toast.makeText(context,"尚未安裝股市籌碼K線 App，請先安裝後再點擊股票卡片",Toast.LENGTH_LONG).show();return
        }
        fun copyCode(){runCatching{(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("股號",code))}}
        fun launchApp(assisted:Boolean){
            copyCode()
            if(assisted)ChipKSearchRequest.put(context,code,name) else ChipKSearchRequest.clear(context)
            if(stock.resolveActivity(context.packageManager)!=null&&runCatching{context.startActivity(stock)}.isSuccess)return
            if(launch!=null&&runCatching{context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.isSuccess){
                if(!assisted)Toast.makeText(context,"$code $name 股號已複製，請貼入籌碼K線搜尋",Toast.LENGTH_LONG).show()
            }else{ChipKSearchRequest.clear(context);Toast.makeText(context,"無法開啟籌碼K線 App",Toast.LENGTH_LONG).show()}
        }
        if(ChipKSearchRequest.enabled(context)){launchApp(true);return}
        if(stock.resolveActivity(context.packageManager)!=null){launchApp(false);return}
        if(context is android.app.Activity){
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("啟用籌碼K線自動搜尋")
                .setMessage("首次使用請在 Android 設定中啟用「籌碼K線搜尋輔助」。只在你點擊股票卡片後，讀取籌碼K線畫面中的搜尋欄與圖表按鈕，輸入股號、選取股票並切換日K。畫面內容不會儲存或上傳，也不操作買賣下單。\n\n啟用後即可自動開啟 $code $name；之後點擊卡片不必再次設定。")
                .setNegativeButton("先開啟 App"){_,_->launchApp(false)}
                .setPositiveButton("前往啟用"){_,_->
                    copyCode();ChipKSearchRequest.put(context,code,name)
                    runCatching{context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))}
                        .onFailure{ChipKSearchRequest.clear(context);launchApp(false)}
                }.show()
        }else launchApp(false)
    }
}
