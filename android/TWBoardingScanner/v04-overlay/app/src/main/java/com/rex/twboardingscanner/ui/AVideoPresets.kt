package com.rex.twboardingscanner.ui
import android.content.Context
import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.APattern
internal object AVideoPresets {
    fun add(c:Context,parent:LinearLayout,pick:(Set<String>)->Unit){
        parent.addView(NeonUi.label(c,"影片策略加入 A：選一套會替換本區勾選，儲存才生效。三階段分開測試，避免同日同時要求放量及縮量。",12f,NeonUi.amber))
        listOf("準備觀察" to APattern.PREPARE,"突破確認" to APattern.CONFIRMED,"回踩再轉強" to APattern.PULLBACK).forEachIndexed{i,p->
            parent.addView(NeonUi.button(c,p.first){pick(p.second)}.apply{tag="a-video-preset-$i"})
        }
        parent.addView(NeonUi.label(c,"三套皆含股價≥50、量≥500張、避免過熱、MACD起轉。初始固定門檻：量縮60%、平台20日/15%、突破0.5%/1.5倍、收盤位置70%/上影30%；回踩1～10日/±2%/均量70%/收盤守99%。完成日K判斷；未保證勝率。可另勾均線順排或連續放量；無可靠籌碼資料不推定主力鎖倉。",11f))
    }
}
