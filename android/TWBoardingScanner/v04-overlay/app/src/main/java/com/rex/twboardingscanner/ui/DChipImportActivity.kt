package com.rex.twboardingscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rex.twboardingscanner.data.DChipStore

/** Source-labelled daily chip import used only by D opt-in checks. */
class DChipImportActivity:AppCompatActivity(){
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null)return@registerForActivityResult
        Thread{
            val result=runCatching{
                contentResolver.openInputStream(uri)?.use{DChipStore(this).importCsv(it)}
                    ?:error("無法讀取選取的 CSV")
            }
            runOnUiThread{
                if(!isFinishing&&!isDestroyed)result.fold({n->
                    Toast.makeText(this,"已匯入 $n 筆 D 籌碼資料；請重新掃描，回測／機器人建立新批次",Toast.LENGTH_LONG).show()
                },{e->Toast.makeText(this,"匯入失敗：${e.message}",Toast.LENGTH_LONG).show()})
            }
        }.start()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=NeonUi.vertical(this).apply{
            setPadding(NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,16),NeonUi.dp(context,24))
        }
        val scroll=ScrollView(this).apply{background=NeonUi.backdrop(context);addView(root)}
        NeonPageChrome.attach(this,scroll,NeonPageChrome.Page.SCAN)
        ViewCompat.setOnApplyWindowInsetsListener(scroll){view,insets->
            val b=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(b.left,b.top,b.right,b.bottom);insets
        }
        root.addView(NeonUi.header(this,"D 主力進場 · 籌碼匯入","D CHIP DATA",
            "每日股東持股、5日集中度與外資／投信買賣超"){finish()})
        root.addView(NeonUi.label(this,
            "資料需由券商或獲授權來源提供，不能用 K 線漲跌或成交量假算。"+ 
            "集保股權分散表通常每週更新，不可以直接當成每日大戶持股變化。",13f,NeonUi.ink))
        root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.primary(this,"匯入 D 條件每日籌碼 CSV"){
            picker.launch(arrayOf("text/*","text/csv","application/octet-stream"))
        }.apply{tag="d-chip-import-csv"})
        root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.section(this,"CSV 範例與單位").apply{
            addView(NeonUi.label(this@DChipImportActivity,DChipStore.EXAMPLE,11f,NeonUi.cyan))
        })
        root.addView(NeonUi.button(this,"複製 CSV 範例"){
            val clip=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("D籌碼CSV範例",DChipStore.EXAMPLE))
            Toast.makeText(this,"已複製範例，請改成你有來源的實際籌碼資料",Toast.LENGTH_SHORT).show()
        })
        root.addView(NeonUi.gap(this,10))
        root.addView(NeonUi.label(this,
            "bigHolderPct、mainForce5Pct：以百分數表示，例如 45.12 代表 45.12%；"+ 
            "大戶增加 0.01% 指 45.12% → 45.13%（增加 0.01 個百分點）。"+
            "foreignNetShares、trustNetShares：以「股」為單位的當日淨買賣超，可為負數。"+
            "只知道其中一部分欄位，可留空其他數字；缺少完整兩日持股比例或主力集中度時不會通過。",12f))
        root.addView(NeonUi.gap(this,12))
        root.addView(NeonUi.label(this,
            "歷史回測與機器人只使用當日 13:25 後完整觀測資料，不能使用隔天才知道的內容。" +
            "當日盤中若尚未取得外資／投信公布的日報，仍顯示待查核。來源名稱為使用者填寫，需自行確認資料授權與口徑。",12f,NeonUi.amber))
        root.addView(NeonUi.label(this,
            "目前來源修訂版本："+DChipStore(this).revision().take(12),11f,NeonUi.muted))
    }
}
