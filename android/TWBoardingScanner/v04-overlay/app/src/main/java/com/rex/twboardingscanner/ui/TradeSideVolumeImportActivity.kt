package com.rex.twboardingscanner.ui

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rex.twboardingscanner.data.TradeSideVolumeStore

/** Shared import entry for main scanner, archived backtest, and auto robot. */
class TradeSideVolumeImportActivity:AppCompatActivity(){
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null)return@registerForActivityResult
        Thread{
            val result=runCatching{
                contentResolver.openInputStream(uri)?.use{TradeSideVolumeStore(this).importCsv(it)}
                    ?:error("無法讀取選取的 CSV")
            }
            runOnUiThread{
                if(!isFinishing&&!isDestroyed)result.fold({count->
                    Toast.makeText(this,"已匯入 $count 組內外盤資料；請重新掃描或建立新回測批次",Toast.LENGTH_LONG).show()
                },{err->
                    Toast.makeText(this,"匯入失敗：${err.message}",Toast.LENGTH_LONG).show()
                })
            }
        }.start()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=NeonUi.vertical(this).apply{
            setPadding(NeonUi.dp(this@TradeSideVolumeImportActivity,16),
                NeonUi.dp(this@TradeSideVolumeImportActivity,16),
                NeonUi.dp(this@TradeSideVolumeImportActivity,16),
                NeonUi.dp(this@TradeSideVolumeImportActivity,20))
        }
        val scroll=ScrollView(this).apply{background=NeonUi.backdrop(context);addView(root)}
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        root.addView(NeonUi.header(this,"主動買賣成交量","TRADE SIDE VOLUME",
            "供 A／B／C 的外盤 ≥ 內盤 1.5 倍條件使用"){finish()})
        root.addView(NeonUi.label(this,
            "必須提供真正的「外盤（主動買進成交量）」與「內盤（主動賣出成交量）」累計成交數據。" +
            "五檔委買／委賣掛單量、當日總成交量、紅黑 K 不能取代這兩個值。",13f,NeonUi.ink))
        root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.button(this,"匯入授權行情 CSV",NeonUi.cyan){
            picker.launch(arrayOf("text/*","text/csv","application/octet-stream"))
        }.apply{tag="flow-import-csv";NeonUi.primaryStyle(this)})
        root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.label(this,"CSV 格式：",14f,NeonUi.ink,true))
        root.addView(NeonUi.label(this,TradeSideVolumeStore.EXAMPLE,11f,NeonUi.cyan))
        root.addView(NeonUi.button(this,"複製 CSV 範例"){
            val manager=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("外內盤CSV",TradeSideVolumeStore.EXAMPLE))
            Toast.makeText(this,"已複製範例；請換成你的真實行情資料",Toast.LENGTH_SHORT).show()
        })
        root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.label(this,
            "欄位：date=日期，code=股號，tradeVolumeAtAsk=主動買進（外盤），tradeVolumeAtBid=主動賣出（內盤），asOf=累計截止時刻，source=資料來源。" +
            "買量和賣量必須同單位，CSV 需由券商／授權逐筆來源匯出，不可以自己把委買委賣掛單量貼進去。",12f))
        root.addView(NeonUi.gap(this,10))
        root.addView(NeonUi.label(this,
            "當日盤中：僅使用截止時刻不晚於掃描時刻的已匯入資料。" +
            "歷史回測／機器人：僅接受完整交易日 13:25 後的累計數據。" +
            "若缺少對應股票及交易日期，訊號顯示「待查核」，勾選後不會通過。" +
            "匯入新資料會暫停舊回測／機器人批次，避免同一批次混用不同時點的數據。",12f,NeonUi.amber))
        root.addView(NeonUi.gap(this,10))
        root.addView(NeonUi.label(this,"目前資料版本："+TradeSideVolumeStore(this).revision().take(12),11f,NeonUi.muted))
    }
}
