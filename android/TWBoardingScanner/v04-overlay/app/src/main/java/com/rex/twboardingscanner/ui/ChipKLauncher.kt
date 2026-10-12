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
        if(stock.resolveActivity(context.packageManager)!=null && runCatching{context.startActivity(stock)}.isSuccess)return
        val launch=context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if(launch!=null) {
            val copied=runCatching {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("股號",code))
            }.isSuccess
            if(runCatching{context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.isSuccess) {
                Toast.makeText(context,if(copied)"$code $name 股號已複製，請貼入籌碼K線搜尋" else "$code $name：請在籌碼K線搜尋此股號",Toast.LENGTH_LONG).show()
                return
            }
        }
        // The app is unavailable: keep the selected stock when opening the official web page.
        runCatching{context.startActivity(stock.setPackage(null))}.onFailure {
            Toast.makeText(context,"無法開啟籌碼K線或瀏覽器",Toast.LENGTH_LONG).show()
        }
    }
}
