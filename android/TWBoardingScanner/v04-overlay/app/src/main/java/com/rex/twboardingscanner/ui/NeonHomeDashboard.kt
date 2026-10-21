package com.rex.twboardingscanner.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rex.twboardingscanner.domain.RadarType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reference-inspired, functional Android home screen.
 * Counts come from the real scanner; artwork and dashboard never invent scan results.
 * The legacy scanner view and all its event handlers remain attached behind this home.
 */
internal class NeonHomeDashboard(
    private val activity: Activity,
    private val openScanner: () -> Unit,
    private val openBacktest: () -> Unit,
    private val openRobot: () -> Unit,
    private val openUpdate: () -> Unit,
    private val openCondition: (RadarType) -> Unit,
    private val openDiagnostics: () -> Unit,
    private val openPermissions: () -> Unit,
    private val openCsv: () -> Unit,
    private val openChangelog: () -> Unit
) {
    data class Stats(
        val scanned: Int?,
        val matched: Int?,
        val fiveOfSix: Int?,
        val sixOfSix: Int?,
        val timestampMs: Long?,
        val title: String,
        val details: String,
        val running: Boolean
    )

    private val white=Color.rgb(249,241,255)
    private val lavender=Color.rgb(204,173,255)
    private val purple=Color.rgb(185,56,255)
    private val cyan=Color.rgb(70,228,221)
    private val pink=Color.rgb(255,88,185)
    private val numberViews=ArrayList<TextView>(4)
    private lateinit var updated: TextView
    private lateinit var status: TextView
    private lateinit var statusDetails: TextView
    val view: FrameLayout=FrameLayout(activity).apply {
        background=NightSky(activity.resources.displayMetrics.density)
        isFocusable=true
        contentDescription="AI-離職神器，主頁儀表板"
    }

    private fun dp(value:Int):Int=(value*activity.resources.displayMetrics.density+.5f).toInt()

    private fun text(value:String, size:Float, color:Int=white, bold:Boolean=false):TextView =
        TextView(activity).apply {
            text=value
            textSize=size
            setTextColor(color)
            includeFontPadding=false
            gravity=Gravity.CENTER_VERTICAL
            if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        }

    private fun gradient(start:Int,end:Int,radius:Int=15,stroke:Int=purple):GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(start,end)).apply {
            cornerRadius=dp(radius).toFloat()
            setStroke(dp(1).coerceAtLeast(1),stroke)
        }

    private fun vertical():LinearLayout=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    private fun horizontal():LinearLayout=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun gap(height:Int):View=View(activity).apply{layoutParams=LinearLayout.LayoutParams(1,dp(height))}
    private fun space(parent:LinearLayout,h:Int){parent.addView(gap(h))}
    private fun clickable(target:View,description:String,onClick:()->Unit){
        target.contentDescription=description
        target.isClickable=true
        target.isFocusable=true
        target.setOnClickListener{onClick()}
    }
    private fun equalRow(children:List<View>, spacing:Int=5):LinearLayout=horizontal().apply{
        children.forEachIndexed{index,child->
            addView(child,LinearLayout.LayoutParams(0,-2,1f).apply{
                if(index>0)marginStart=dp(spacing)
            })
        }
    }
    private fun section():LinearLayout=vertical().apply{
        setPadding(dp(9),dp(11),dp(9),dp(11))
        background=gradient(Color.rgb(36,13,79),Color.rgb(16,6,42),18,Color.rgb(166,62,236))
        elevation=dp(4).toFloat()
    }
    private fun heading(name:String,subtitle:String):LinearLayout=horizontal().apply {
        addView(text(name,15f,white,true),LinearLayout.LayoutParams(0,-2,1f))
        val caption=text(subtitle,9.3f,lavender).apply{gravity=Gravity.END or Gravity.CENTER_VERTICAL;maxLines=1}
        addView(caption,LinearLayout.LayoutParams(-2,-2))
    }

    init {
        val scroll=ScrollView(activity).apply {
            isFillViewport=false
            isVerticalScrollBarEnabled=false
            clipToPadding=false
            overScrollMode=View.OVER_SCROLL_NEVER
        }
        val body=vertical().apply{
            setPadding(dp(10),dp(8),dp(10),dp(24))
        }
        scroll.addView(body,ScrollView.LayoutParams(-1,-2))
        view.addView(scroll,FrameLayout.LayoutParams(-1,-1))

        val header=horizontal().apply{gravity=Gravity.CENTER_VERTICAL}
        val logo=DiamondLogo(activity)
        header.addView(logo,LinearLayout.LayoutParams(dp(103),dp(106)))
        val nameCol=vertical()
        val appName=text("AI-離職神器",29f,white,true).apply {
            isSingleLine=true
            setAutoSizeTextTypeUniformWithConfiguration(19,29,1,TypedValue.COMPLEX_UNIT_SP)
            setShadowLayer(dp(6).toFloat(),0f,0f,purple)
        }
        nameCol.addView(appName,LinearLayout.LayoutParams(-1,dp(44)))
        val authorRow=horizontal().apply {
            addView(text("作者：雷允澤",12.5f,white),LinearLayout.LayoutParams(0,-2,1f))
            val version=runCatching{activity.packageManager.getPackageInfo(activity.packageName,0).versionName}
                .getOrNull().orEmpty().ifBlank{"—"}
            addView(text("v"+version,10.5f,white).apply{
                setPadding(dp(9),dp(4),dp(9),dp(4))
                background=gradient(Color.rgb(55,32,102),Color.rgb(36,20,74),25,Color.rgb(111,60,177))
            })
        }
        nameCol.addView(authorRow)
        space(nameCol,7)
        nameCol.addView(text("用 數 據  找 機 會",10.5f,lavender),LinearLayout.LayoutParams(-1,-2))
        header.addView(nameCol,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(10)})
        val notice=Glyph(activity,"bell",white)
        clickable(notice,"查看掃描狀態"){openScanner()}
        header.addView(notice,LinearLayout.LayoutParams(dp(24),dp(28)).apply{marginStart=dp(4)})
        body.addView(header)
        space(body,10)

        val actions=listOf(
            action("主掃描","找出潛力標的","target",Color.rgb(157,34,255),openScanner),
            action("歷史回測","驗證策略績效","bars",Color.rgb(33,101,249),openBacktest),
            action("自動測試機器人","全自動策略測試","robot",Color.rgb(222,35,130),openRobot),
            action("一鍵更新","取得最新版本","refresh",Color.rgb(155,44,232),openUpdate)
        )
        body.addView(equalRow(actions,5))
        space(body,11)

        val summary=section()
        summary.addView(heading("▥  今日掃描結果","真實掃描資料  ●"))
        space(summary,7)
        val sep=View(activity).apply{setBackgroundColor(Color.rgb(84,43,134))}
        summary.addView(sep,LinearLayout.LayoutParams(-1,dp(1)))
        space(summary,8)
        summary.addView(equalRow(listOf(
            metric("◎","今日掃描",cyan),
            metric("✓","符合條件",cyan),
            metric("▤","5/6 名單",Color.rgb(147,169,255)),
            metric("♛","6/6 名單",pink)
        ),2))
        space(summary,5)
        updated=text("尚無掃描紀錄",9.5f,lavender).apply{gravity=Gravity.END or Gravity.CENTER_VERTICAL}
        summary.addView(updated)
        body.addView(summary)
        space(body,10)

        val conditions=section()
        conditions.addView(heading("✣  條件與工具","點選 A～G 編輯策略  ›"))
        space(conditions,9)
        val types=RadarType.entries
        val first=listOf(
            condition(types[0],"多方趨勢","準備起漲","trend",Color.rgb(143,32,254)),
            condition(types[1],"動能突破","深跌反轉","breakout",Color.rgb(6,96,245)),
            condition(types[2],"籌碼集中","突發爆量","candle",Color.rgb(202,31,97)),
            condition(types[3],"價量齊揚","主力進場","coin",Color.rgb(193,126,38))
        )
        val second=listOf(
            condition(types[4],"基本面優良","DIF 金叉","leaf",Color.rgb(3,178,176)),
            condition(types[5],"技術面強勢","縮量上漲","bars",Color.rgb(34,89,227)),
            condition(types[6],"特殊指標","大陽線波段","diamond",Color.rgb(213,29,199))
        )
        conditions.addView(equalRow(first,5))
        space(conditions,5)
        conditions.addView(equalRow(second,5))
        space(conditions,10)
        conditions.addView(heading("🔧  實用工具","提升效率與穩定性"))
        space(conditions,7)
        conditions.addView(equalRow(listOf(
            tool("背景工作診斷","檢查系統狀態","gear",Color.rgb(98,75,170),openDiagnostics),
            tool("權限設定","管理必要權限","shield",Color.rgb(82,81,170),openPermissions),
            tool("匯入 CSV","匯入自選標的","csv",Color.rgb(79,89,178),openCsv),
            tool("版本日誌","查看更新內容","file",Color.rgb(124,75,173),openChangelog)
        ),5))
        body.addView(conditions)
        space(body,10)

        val recent=section().apply{
            background=gradient(Color.rgb(63,14,101),Color.rgb(20,4,48),17,Color.rgb(215,39,255))
        }
        recent.addView(heading("▣  最近消息","查看掃描結果  ›").also{
            clickable(it,"查看掃描結果",openScanner)
        })
        space(recent,7)
        val inner=horizontal().apply{
            setPadding(dp(12),dp(10),dp(10),dp(11))
            background=gradient(Color.rgb(34,12,68),Color.rgb(19,8,44),15,Color.rgb(120,44,190))
        }
        val dot=View(activity).apply {
            background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.rgb(28,214,143))}
        }
        inner.addView(dot,LinearLayout.LayoutParams(dp(9),dp(9)).apply{marginEnd=dp(9)})
        val statusCol=vertical()
        status=text("系統待命中",12.5f,Color.rgb(77,230,179),true)
        statusDetails=text("尚未開始掃描",10f,lavender).apply{maxLines=2}
        statusCol.addView(status)
        space(statusCol,4)
        statusCol.addView(statusDetails)
        inner.addView(statusCol,LinearLayout.LayoutParams(0,-2,1f))
        inner.addView(text("⋯",20f,lavender))
        clickable(inner,"查看掃描詳情",openScanner)
        recent.addView(inner)
        body.addView(recent)
    }

    private fun action(title:String,subtitle:String,icon:String,color:Int,run:()->Unit):View {
        val tile=vertical().apply{
            gravity=Gravity.CENTER
            minimumHeight=dp(77)
            setPadding(dp(2),dp(8),dp(2),dp(7))
            background=gradient(color,Color.rgb(32,12,82),15,color)
            elevation=dp(5).toFloat()
        }
        tile.addView(Glyph(activity,icon,white),LinearLayout.LayoutParams(dp(30),dp(28)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        space(tile,4)
        tile.addView(text(title,11.4f,white,true).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(-1,-2))
        space(tile,3)
        tile.addView(text(subtitle,9f,lavender).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(-1,-2))
        clickable(tile,title+"，"+subtitle,run)
        return tile
    }

    private fun metric(icon:String,title:String,color:Int):View=vertical().apply{
        gravity=Gravity.CENTER
        setPadding(dp(1),dp(2),dp(1),dp(3))
        addView(text(icon+"  "+title,10.2f,white,true).apply{
            gravity=Gravity.CENTER;maxLines=1
            setAutoSizeTextTypeUniformWithConfiguration(8,11,1,TypedValue.COMPLEX_UNIT_SP)
        },LinearLayout.LayoutParams(-1,dp(20)))
        val count=text("—",25.5f,color,true).apply{gravity=Gravity.CENTER}
        numberViews.add(count)
        addView(count,LinearLayout.LayoutParams(-1,dp(36)))
        addView(text("檔",9.5f,lavender).apply{gravity=Gravity.CENTER})
        clickable(this,title+"，查看結果",openScanner)
    }

    private fun condition(type:RadarType,title:String,meaning:String,glyph:String,color:Int):View {
        val tile=vertical().apply{
            setPadding(dp(8),dp(6),dp(5),dp(6))
            minimumHeight=dp(70)
            background=gradient(color,Color.rgb(28,12,69),12,color)
        }
        val top=horizontal()
        top.addView(text(type.name.take(1),22f,white,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(Glyph(activity,glyph,white),LinearLayout.LayoutParams(dp(23),dp(23)))
        tile.addView(top)
        tile.addView(text(title,11.3f,white,true).apply{gravity=Gravity.CENTER;maxLines=1},LinearLayout.LayoutParams(-1,-2))
        space(tile,2)
        tile.addView(text(meaning,9f,lavender).apply{gravity=Gravity.CENTER;maxLines=1},LinearLayout.LayoutParams(-1,-2))
        clickable(tile,type.label+"，開啟條件編輯"){openCondition(type)}
        return tile
    }

    private fun tool(title:String,subtitle:String,icon:String,color:Int,run:()->Unit):View{
        val tile=vertical().apply{
            minimumHeight=dp(65)
            setPadding(dp(3),dp(7),dp(3),dp(6))
            gravity=Gravity.CENTER
            background=gradient(color,Color.rgb(26,14,65),13,Color.rgb(116,93,206))
        }
        tile.addView(Glyph(activity,icon,white),LinearLayout.LayoutParams(dp(24),dp(23)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        space(tile,3)
        tile.addView(text(title,10.7f,white,true).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(-1,-2))
        space(tile,1)
        tile.addView(text(subtitle,8.5f,lavender).apply{gravity=Gravity.CENTER;maxLines=2})
        clickable(tile,title+"，"+subtitle,run)
        return tile
    }

    fun update(stats:Stats){
        val values=listOf(stats.scanned,stats.matched,stats.fiveOfSix,stats.sixOfSix)
        numberViews.forEachIndexed{index,v->v.text=values[index]?.toString()?:"—"}
        updated.text=stats.timestampMs?.takeIf{it>0L}?.let{
            "更新 "+SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.TAIWAN).format(Date(it))
        }?:"尚無掃描紀錄"
        status.text=stats.title.ifBlank{if(stats.running)"掃描中" else "系統待命中"}
        statusDetails.text=stats.details.ifBlank{"尚未開始掃描"}
        status.setTextColor(if(stats.running)Color.rgb(81,242,178) else lavender)
    }
}

/** Static illustration keeps all lettering and numbers as actual accessible Android views. */
private class NightSky(private val density:Float):Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c:Canvas){
        val w=bounds.width().toFloat()
        val h=bounds.height().toFloat()
        if(w<=0f||h<=0f)return
        c.save()
        c.translate(bounds.left.toFloat(),bounds.top.toFloat())
        p.style=Paint.Style.FILL
        p.shader=LinearGradient(0f,0f,0f,h,
            intArrayOf(Color.rgb(28,3,49),Color.rgb(13,4,35),Color.rgb(9,3,26)),null,Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,w,h,p)
        p.shader=null
        val peak=Path().apply{
            moveTo(w*.25f,142*density)
            lineTo(w*.49f,8*density)
            lineTo(w*.55f,60*density)
            lineTo(w*.64f,28*density)
            lineTo(w*.83f,172*density)
            close()
        }
        p.shader=LinearGradient(w*.55f,0f,w*.7f,190*density,
            intArrayOf(Color.rgb(98,31,148),Color.rgb(15,6,46)),null,Shader.TileMode.CLAMP)
        c.drawPath(peak,p);p.shader=null
        p.color=Color.argb(90,207,79,255)
        p.style=Paint.Style.STROKE;p.strokeWidth=1.2f*density
        val ridge=Path().apply{
            moveTo(w*.49f,8*density);lineTo(w*.51f,58*density)
            lineTo(w*.57f,79*density);lineTo(w*.62f,43*density)
        }
        c.drawPath(ridge,p)
        val candles=floatArrayOf(.84f,.78f,.72f,.75f,.60f,.68f,.52f,.57f,.46f,.39f,.47f,.32f,.27f)
        for(i in candles.indices){
            val x=w*(.62f+.031f*i)
            val y=37*density+100*density*candles[i]
            val grow=i%4!=0
            p.color=if(grow)Color.argb(170,240,45,217) else Color.argb(150,103,93,255)
            p.strokeWidth=1.1f*density
            c.drawLine(x,y-10*density,x,y+11*density,p)
            p.style=Paint.Style.FILL
            c.drawRect(x-2.2f*density,y-5*density,x+2.2f*density,y+6*density,p)
            p.style=Paint.Style.STROKE
        }
        p.color=Color.argb(90,175,35,255)
        p.strokeWidth=1f*density
        c.drawRoundRect(RectF(1*density,1*density,w-1*density,h-1*density),18*density,18*density,p)
        p.style=Paint.Style.FILL
        c.restore()
    }
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(colorFilter:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
}

private class DiamondLogo(ctx:Context):View(ctx){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        val s=minOf(width,height).toFloat()
        c.save()
        c.translate((width-s)/2f,(height-s)/2f)
        c.scale(s/100f,s/100f)
        p.style=Paint.Style.FILL
        p.shader=LinearGradient(0f,0f,100f,100f,intArrayOf(Color.rgb(104,17,177),Color.rgb(15,4,60)),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(2f,2f,98f,98f,20f,20f,p)
        p.shader=null
        p.style=Paint.Style.STROKE
        for(i in 0..2){
            p.color=Color.argb(110-i*27,229,111,255)
            p.strokeWidth=(4-i).toFloat()
            c.drawRoundRect(3f+i,3f+i,97f-i,97f-i,19f,19f,p)
        }
        p.style=Paint.Style.FILL
        val outline=Path().apply{
            moveTo(11f,42f);lineTo(29f,21f);lineTo(67f,18f);lineTo(89f,42f)
            lineTo(50f,91f);close()
        }
        p.shader=LinearGradient(20f,20f,75f,90f,
            intArrayOf(Color.rgb(242,184,255),Color.rgb(133,37,252),Color.rgb(36,9,138)),null,Shader.TileMode.CLAMP)
        c.drawPath(outline,p);p.shader=null
        val left=Path().apply{moveTo(11f,42f);lineTo(29f,21f);lineTo(37f,42f);lineTo(50f,91f);close()}
        p.color=Color.argb(160,242,188,255);c.drawPath(left,p)
        val mid=Path().apply{moveTo(37f,42f);lineTo(67f,18f);lineTo(65f,44f);lineTo(50f,91f);close()}
        p.color=Color.argb(110,93,15,219);c.drawPath(mid,p)
        val right=Path().apply{moveTo(65f,44f);lineTo(89f,42f);lineTo(50f,91f);close()}
        p.color=Color.argb(130,53,7,132);c.drawPath(right,p)
        p.color=Color.argb(180,248,225,255)
        p.style=Paint.Style.STROKE;p.strokeWidth=.9f
        c.drawPath(outline,p)
        p.style=Paint.Style.FILL
        p.textAlign=Paint.Align.CENTER
        p.typeface=Typeface.create("sans-serif-black",Typeface.BOLD)
        p.textSize=43f
        p.setShadowLayer(4f,0f,0f,Color.rgb(248,217,255))
        p.color=Color.WHITE;c.drawText("$",51f,61f,p);p.clearShadowLayer()
        c.restore()
    }
}

private class Glyph(ctx:Context,private val kind:String,private val tint:Int):View(ctx){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        c.save()
        c.translate((width-width.coerceAtMost(height))/2f,(height-height.coerceAtMost(width))/2f)
        val size=minOf(width,height).toFloat()
        c.scale(size/48f,size/48f)
        p.color=tint;p.style=Paint.Style.STROKE;p.strokeWidth=2.8f
        p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        fun line(vararg points:Float){
            val path=Path().apply{
                moveTo(points[0],points[1])
                for(i in 2 until points.size step 2)lineTo(points[i],points[i+1])
            }
            c.drawPath(path,p)
        }
        fun rect(l:Float,t:Float,r:Float,b:Float,rad:Float=2f){
            c.drawRoundRect(l,t,r,b,rad,rad,p)
        }
        when(kind){
            "target"->{
                c.drawCircle(24f,24f,14f,p);c.drawCircle(24f,24f,6f,p)
                line(24f,3f,24f,13f);line(24f,35f,24f,45f)
                line(3f,24f,13f,24f);line(35f,24f,45f,24f)
            }
            "bars"->{
                rect(5f,26f,13f,42f);rect(20f,16f,28f,42f);rect(35f,6f,43f,42f)
                line(4f,44f,44f,44f)
            }
            "robot"->{
                rect(7f,14f,41f,39f,7f);line(24f,6f,24f,14f)
                c.drawCircle(24f,4f,2f,p);c.drawCircle(17f,25f,2.5f,p)
                c.drawCircle(31f,25f,2.5f,p);line(18f,34f,30f,34f)
                line(2f,24f,7f,24f);line(41f,24f,46f,24f)
            }
            "refresh"->{
                c.drawArc(RectF(8f,9f,40f,39f),40f,270f,false,p)
                line(38f,10f,38f,20f,28f,19f)
                line(10f,39f,10f,29f,20f,30f)
            }
            "trend","breakout"->{
                line(7f,38f,19f,27f,27f,31f,41f,9f)
                line(30f,9f,41f,9f,41f,20f)
            }
            "candle"->{
                line(11f,5f,11f,44f);rect(6f,18f,16f,33f)
                line(25f,6f,25f,42f);rect(20f,12f,30f,25f)
                line(39f,4f,39f,45f);rect(34f,23f,44f,35f)
            }
            "coin"->{
                c.drawOval(RectF(5f,8f,26f,15f),p)
                line(5f,12f,5f,34f);line(26f,12f,26f,34f)
                c.drawArc(RectF(5f,25f,26f,35f),0f,180f,false,p)
                c.drawOval(RectF(26f,21f,44f,28f),p)
                line(26f,25f,26f,39f);line(44f,25f,44f,39f)
                c.drawArc(RectF(26f,33f,44f,43f),0f,180f,false,p)
            }
            "leaf"->{
                val path=Path().apply{
                    moveTo(10f,40f);cubicTo(7f,18f,25f,8f,43f,6f)
                    cubicTo(45f,32f,28f,43f,10f,40f)
                }
                c.drawPath(path,p);line(9f,42f,32f,20f)
            }
            "diamond"->{
                line(5f,16f,16f,7f,34f,7f,44f,16f,24f,43f,5f,16f)
                line(5f,16f,44f,16f);line(16f,7f,20f,16f,24f,43f)
                line(34f,7f,29f,16f,24f,43f)
            }
            "gear"->{
                c.drawCircle(24f,24f,10f,p)
                c.drawCircle(24f,24f,4f,p)
                for(i in 0..7){
                    val a=Math.PI*i/4
                    val x=Math.cos(a).toFloat();val y=Math.sin(a).toFloat()
                    line(24f+15f*x,24f+15f*y,24f+20f*x,24f+20f*y)
                }
            }
            "shield"->{
                line(24f,4f,41f,11f,39f,30f,24f,44f,9f,30f,7f,11f,24f,4f)
                line(24f,16f,24f,31f)
            }
            "csv","file"->{
                line(12f,5f,31f,5f,39f,13f,39f,44f,12f,44f,12f,5f)
                line(31f,5f,31f,13f,39f,13f)
                line(18f,25f,33f,25f);line(18f,32f,31f,32f)
            }
            "bell"->{
                c.drawArc(RectF(10f,8f,38f,41f),190f,160f,false,p)
                line(10f,34f,38f,34f);line(20f,40f,28f,40f)
                c.drawCircle(24f,7f,2f,p)
            }
            else->c.drawCircle(24f,24f,15f,p)
        }
        c.restore()
    }
}
