package com.rex.twboardingscanner.ui

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import com.rex.twboardingscanner.domain.RadarType

/** A single source of truth for what is exported, previewed, saved, and shared. */
internal data class PosterSection(val radar:RadarType,val letter:String,val label:String)

internal object PosterCatalog {
    val sections:List<PosterSection> = RadarType.entries.map{type->
        when(type){
            RadarType.A_EARLY_BREAKOUT -> PosterSection(type,"A","A 起漲")
            RadarType.B_DEEP_REVERSAL -> PosterSection(type,"B","B 反轉")
            RadarType.C_LONG_RED_VOLUME -> PosterSection(type,"C","C 爆量")
            RadarType.D_MAIN_FORCE -> PosterSection(type,"D","D 主力")
            RadarType.E_DIF_TOUCH -> PosterSection(type,"E","E DIF 接觸")
        }
    }
    val count:Int get()=sections.size
    val summary:String get()=sections.joinToString("／"){it.letter}
    fun fileName(day:String,section:PosterSection)="台股掃描_${day}_${section.letter}.png"

    /**
     * Android's BitmapFactory must not decode enormous posters at full resolution
     * on the main thread. Power-of-two samples cap the whole preview in both axes.
     */
    fun previewSample(width:Int,height:Int,maxWidth:Int=880,maxHeight:Int=1760):Int {
        require(width>0&&height>0&&maxWidth>0&&maxHeight>0)
        var factor=1
        while(width.toLong()>factor.toLong()*maxWidth||
            height.toLong()>factor.toLong()*maxHeight){
            require(factor<65536){"海報過大，無法預覽；仍可儲存原圖"}
            factor*=2
        }
        return factor
    }

    fun tabs(context:Context,select:(Int)->Unit):LinearLayout {
        val container=LinearLayout(context).apply{
            orientation=LinearLayout.HORIZONTAL
            contentDescription="切換 ${summary} 區海報預覽"
        }
        sections.forEachIndexed{i,section->
            container.addView(Button(context).apply{
                text=section.label
                tag="poster-tab-${section.letter}"
                textSize=11f
                isAllCaps=false
                setSingleLine(false)
                maxLines=2
                contentDescription="預覽 ${section.label} 海報"
                setOnClickListener{select(i)}
            },LinearLayout.LayoutParams(0,(44*context.resources.displayMetrics.density).toInt(),1f))
        }
        return container
    }
}
