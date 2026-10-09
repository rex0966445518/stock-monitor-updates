package com.rex.twboardingscanner.ui

import android.graphics.*
import com.rex.twboardingscanner.domain.*
import java.util.Locale
import kotlin.math.*

/** Drawn from the same immutable results as the screen; decorative elements carry no invented data. */
internal class NeonPoster(private val radar:RadarType,private val stocks:List<PosterStock>,
    private val day:String,private val time:String,private val scanning:Boolean,
    private val counts:List<Int>,private val done:Int,private val total:Int) {
    val height=maxOf(1540,890+stocks.size*350)
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val white=Color.rgb(235,245,255);private val muted=Color.rgb(150,181,209)
    private val pink=Color.rgb(255,75,121);private val blue=Color.rgb(52,171,255)
    private val mint=Color.rgb(70,246,197);private val gold=Color.rgb(255,190,70)
    private val accent=when(radar){RadarType.A_EARLY_BREAKOUT->pink;RadarType.B_DEEP_REVERSAL->gold;else->blue}
    private val letter=when(radar){RadarType.A_EARLY_BREAKOUT->"A";RadarType.B_DEEP_REVERSAL->"B";else->"C"}
    private val title=when(radar){RadarType.A_EARLY_BREAKOUT->"早期起漲";RadarType.B_DEEP_REVERSAL->"深跌反轉";else->"長紅爆量"}
    private val charts=stocks.map { ChartSeries.prepare(it.bars).takeLast(36) }
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int=white,bold:Boolean=false,maxWidth:Float=1000f) {
        p.shader=null;p.style=Paint.Style.FILL;p.color=color;p.textSize=size;p.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        var value=s;while(p.measureText(value)>maxWidth && value.length>1)value=value.dropLast(1)
        if(value!=s)value=value.dropLast(1)+"…"
        c.drawText(value,x,y,p)
    }
    private fun panel(c:Canvas,x:Float,y:Float,w:Float,h:Float,color:Int=blue,glow:Boolean=false) {
        val r=RectF(x,y,x+w,y+h)
        p.style=Paint.Style.FILL;p.color=Color.WHITE;p.shader=LinearGradient(x,y,x+w,y+h,intArrayOf(Color.rgb(9,35,61),Color.rgb(3,19,36),Color.rgb(8,32,53)),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(r,20f,20f,p);p.shader=null
        if(glow){p.style=Paint.Style.STROKE;for(i in 9 downTo 1){p.strokeWidth=i*2f;p.color=Color.argb(12,color shr 16 and 255,color shr 8 and 255,color and 255);c.drawRoundRect(r,20f,20f,p)}}
        p.style=Paint.Style.STROKE;p.color=Color.WHITE;p.strokeWidth=if(glow)2f else 1f
        p.shader=LinearGradient(x,y,x+w,y+h,intArrayOf(color,Color.rgb(34,65,107),blue),null,Shader.TileMode.CLAMP);c.drawRoundRect(r,20f,20f,p);p.shader=null;p.style=Paint.Style.FILL
    }
    private fun line(c:Canvas,x:Float,y:Float,xx:Float,yy:Float,color:Int,width:Float=1f){p.shader=null;p.style=Paint.Style.STROKE;p.color=color;p.strokeWidth=width;c.drawLine(x,y,xx,yy,p);p.style=Paint.Style.FILL}
    private fun badge(c:Canvas,x:Float,y:Float,size:Float) {
        val path=Path();for(i in 0..5){val angle=(i*60-30)*PI/180;val xx=x+cos(angle).toFloat()*size;val yy=y+sin(angle).toFloat()*size;if(i==0)path.moveTo(xx,yy)else path.lineTo(xx,yy)};path.close()
        p.color=Color.WHITE;p.shader=RadialGradient(x,y,size,accent,Color.rgb(8,23,46),Shader.TileMode.CLAMP);c.drawPath(path,p);p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=accent;p.setShadowLayer(15f,0f,0f,accent);c.drawPath(path,p);p.clearShadowLayer();p.style=Paint.Style.FILL
        text(c,letter,x-size*.36f,y+size*.38f,size*1.2f,white,true)
    }
    fun draw(c:Canvas,top:Int,band:Int) {
        // Fine market-grid texture and diagonal light trails echo the supplied reference.
        for(y in (top/48)*48..top+band step 48) line(c,0f,y.toFloat(),1080f,y.toFloat(),Color.rgb(7,27,47))
        for(x in 0..1080 step 64)line(c,x.toFloat(),top.toFloat(),x.toFloat(),(top+band).toFloat(),Color.rgb(6,23,42))
        for(i in 0..5)line(c,600f+i*88,0f,100f+i*88,720f,Color.argb(25,33,94,210),2f)
        if(top<720){
            panel(c,32f,38f,128f,120f,pink,true)
            line(c,54f,124f,81f,90f,pink,10f);line(c,81f,90f,99f,108f,pink,10f);line(c,99f,108f,136f,64f,pink,10f)
            line(c,111f,68f,136f,64f,pink,8f);line(c,136f,64f,132f,90f,pink,8f)
            text(c,"台股上車掃描器",185f,96f,53f,white,true)
            text(c,"讓數據，幫你看見下一個機會",187f,141f,25f,muted)
            text(c,"最新掃描快照",824f,63f,20f,blue)
            text(c,day,824f,97f,24f,white,true)
            text(c,"$time 台灣時間",824f,132f,20f,muted)
            val labels=listOf("掃描狀態","已處理股數","A 早期起漲","B 深跌反轉","C 長紅爆量")
            val values=listOf(if(scanning)"掃描中" else "已完成","$done / $total", "${counts.getOrElse(0){0}} 檔","${counts.getOrElse(1){0}} 檔","${counts.getOrElse(2){0}} 檔")
            val colors=listOf(mint,blue,pink,gold,blue)
            for(i in 0..4){val x=32f+i*206;panel(c,x,190f,192f,138f,colors[i]);text(c,labels[i],x+15,230f,22f,muted);text(c,values[i],x+15,279f,if(i==1)27f else 35f,colors[i],true,165f);text(c,if(i<2)"本輪掃描" else "目前符合條件",x+15,310f,17f,muted)}
            panel(c,32f,353f,1016f,176f,accent,true)
            val cx=119f;val cy=440f
            for(r in listOf(22f,42f,64f)){p.color=blue;p.alpha=170;p.style=Paint.Style.STROKE;p.strokeWidth=2f;c.drawCircle(cx,cy,r,p)};p.alpha=255;p.style=Paint.Style.FILL
            p.shader=SweepGradient(cx,cy,intArrayOf(Color.TRANSPARENT,Color.rgb(24,83,163),pink),null);c.drawCircle(cx,cy,60f,p);p.shader=null
            line(c,cx-65,cy,cx+65,cy,blue);line(c,cx,cy-65,cx,cy+65,blue);p.color=mint;c.drawCircle(cx,cy,6f,p)
            text(c,if(scanning)"全市場掃描進行中" else "本輪掃描結果",214f,400f,31f,white,true)
            text(c,"$letter 區｜$title  ·  目前 ${stocks.size} 檔",214f,442f,28f,accent,true)
            panel(c,215f,464f,574f,16f,blue)
            val progress=if(total>0)(done.toFloat()/total).coerceIn(0f,1f) else 0f
            if(progress>0){p.shader=LinearGradient(215f,0f,789f,0f,pink,blue,Shader.TileMode.CLAMP);c.drawRoundRect(215f,464f,215f+574*progress,480f,8f,8f,p);p.shader=null}
            text(c,"點擊匯出時的最新資料",215f,511f,20f,muted)
            text(c,String.format(Locale.TAIWAN,"%.1f%%",progress*100),849f,443f,38f,white,true)
            text(c,"已處理進度",853f,480f,19f,muted)
            val sectorGroups=stocks.groupingBy{it.sector}.eachCount().entries.sortedByDescending{it.value}.take(5)
            val chips=listOf("本區全部 ${stocks.size}")+sectorGroups.map{"${it.key} ${it.value}"}
            val width=(1016f-12*(chips.size-1))/chips.size
            chips.forEachIndexed { i,label->val x=32+i*(width+12);panel(c,x,551f,width,69f,if(i==0)accent else blue,i==0);text(c,label,x+12,594f,21f,if(i==0)white else muted,true,width-24)}
            panel(c,32f,645f,1016f,54f,accent)
            text(c,"◆  $letter 區｜$title  ·  符合條件的股票（${stocks.size}）",52f,681f,27f,white,true)
        }
        val first=((top-719)/350).coerceAtLeast(0);val last=((top+band-701)/350).coerceAtMost(stocks.lastIndex)
        if(last>=first) for(i in first..last) card(c,stocks[i],charts[i],720f+i*350)
        if(stocks.isEmpty() && top+band>740 && top<1250){panel(c,32f,730f,1016f,470f,accent,true);badge(c,540f,858f,58f);text(c,"本輪目前沒有符合條件的股票",260f,987f,36f,white,true);text(c,if(scanning)"掃描仍在進行，稍後可再次匯出" else "調整條件並重新掃描後，可匯出新結果",270f,1042f,27f,muted)}
        val footer=height-144f
        if(top+band>footer-15){panel(c,32f,footer,1016f,110f,blue);text(c,"最新結果快照  ·  $day $time",55f,footer+34,23f,white,true);text(c,"A/B 排除當日日K；C 使用最新日K。數值依卡片行情日期。",55f,footer+68,21f,muted);text(c,"條件通過率非投資評級；觀察訊號不保證後續漲幅。",55f,footer+96,20f,muted)}
    }
    private fun card(c:Canvas,s:PosterStock,points:List<ChartPoint>,y:Float) {
        panel(c,32f,y,1016f,332f,accent,true);badge(c,82f,y+53,34f)
        text(c,"${s.code}  ${s.name}",131f,y+53,31f,white,true,350f)
        text(c,s.sector,132f,y+84,19f,muted,maxWidth=325f)
        val col=if(s.change>=0)pink else mint
        text(c,String.format(Locale.TAIWAN,"%,.2f",s.price),131f,y+130,43f,col,true,290f)
        text(c,String.format(Locale.TAIWAN,"%+.2f%%",s.change),365f,y+127,26f,col,true,155f)
        text(c,"條件通過",533f,y+33,17f,muted,maxWidth=90f)
        text(c,"${s.score}%",518f,y+75,33f,accent,true,112f)
        text(c,"依勾選項目",528f,y+99,15f,muted,maxWidth=95f)
        val selected=s.checks.filter{it.selected}
        val names=mapOf("price" to "股價≥50","volume" to "成交量≥500","macd" to "MACD起轉","heat" to "未過熱","converge" to "三線收斂","ma5up" to "5日線翻揚","above3" to "站上三線","rsi" to "RSI>50","contract" to "整理量縮","box" to "突破箱頂","burst" to "突破放量","drawdown" to "深跌≥30%","floor" to "底部守住","higherLow" to "低點墊高","declineVolume" to "下跌量縮","ma5" to "站回5日線","red" to "紅K","gain" to "漲幅≥3%","body" to "實體≥3%","closeHigh" to "收在高檔")
        val featured=selected.sortedBy {if(it.id in listOf("price","volume"))1 else 0}.take(6)
        featured.forEachIndexed { i,check ->val x=53f+(i%3)*190;val yy=y+147+(i/3)*39
            panel(c,x,yy,181f,33f,Color.rgb(35,75,102));val color=when(check.state){CheckState.PASS->mint;CheckState.FAIL->pink;else->gold}
            p.color=color;c.drawCircle(x+17,yy+16,10f,p);text(c,when(check.state){CheckState.PASS->"✓";CheckState.FAIL->"×";else->"?"},x+10,yy+22,17f,Color.rgb(3,30,32),true)
            text(c,names[check.id]?:check.label.substringBefore("："),x+33,yy+23,19f,white,maxWidth=140f)
        }
        val bar=s.bars.lastOrNull();val date=bar?.let{RuleMetrics.tradingDate(it.time).toString()}?:"待查核"
        val volume=bar?.let{String.format(Locale.TAIWAN,"%,.1f",it.volumeShares/1000.0)}?:"待查核"
        text(c,"成交量 $volume 張",53f,y+246,22f,blue,true,maxWidth=360f)
        text(c,"${selected.count{it.state==CheckState.PASS}} / ${selected.size} 項通過",444f,y+246,19f,mint,maxWidth=175f)
        text(c,"日K $date",53f,y+270,17f,muted)
        chart(c,points,647f,y+13,384f,254f)
        val auction=s.auction
        val auctionColor=when(auction?.direction){AuctionDirection.UP->pink;AuctionDirection.DOWN->mint;else->muted}
        line(c,53f,y+280,1027f,y+280,Color.rgb(26,62,88))
        text(c,"${auction?.time?.take(5)?:"13:30"} 收盤撮合",53f,y+312,23f,muted)
        text(c,auction?.display?:"待查核",287f,y+315,32f,auctionColor,true,325f)
        text(c,auction?.date?:"",657f,y+309,19f,muted)
        text(c,"相對收盤前成交價",821f,y+309,17f,muted,maxWidth=211f)
    }
    private fun chart(c:Canvas,points:List<ChartPoint>,x:Float,y:Float,w:Float,h:Float) {
        panel(c,x,y,w,h,Color.rgb(36,80,128));if(points.isEmpty()){text(c,"日K資料待查核",x+55,y+135,23f,muted);return}
        c.save();c.clipRect(x+5,y+4,x+w-4,y+h-4)
        val left=x+12;val right=x+w-48;val priceTop=y+24;val priceBottom=y+113
        val volTop=y+139;val volBottom=y+182;val macTop=y+208;val macBottom=y+244
        text(c,"日 K",left,y+20,16f,muted);text(c,"成交量",left,y+135,15f,muted);text(c,"MACD",left,y+204,15f,muted)
        val values=points.flatMap{listOfNotNull(it.bar.high,it.bar.low,it.ma5,it.ma10,it.ma20)}
        val lo=values.min();val hi=values.max();val range=(hi-lo).coerceAtLeast(.01)
        fun py(v:Double)=priceBottom-((v-lo)/range*(priceBottom-priceTop)).toFloat()
        val maxV=points.maxOf{it.bar.volumeShares}.toDouble().coerceAtLeast(1.0)
        val maxM=(points.flatMap{listOfNotNull(it.dif,it.dea,it.histogram)}.maxOfOrNull{abs(it)}?:.01).coerceAtLeast(.01)
        val zero=(macTop+macBottom)/2;fun my(v:Double)=zero-(v/maxM*(macBottom-macTop)*.46).toFloat()
        for(i in 0..3){val yy=priceTop+(priceBottom-priceTop)*i/3;line(c,left,yy,right,yy,Color.rgb(23,48,69));text(c,String.format(Locale.TAIWAN,"%.0f",hi-range*i/3),right+4,yy+5,12f,muted,maxWidth=40f)}
        for(i in 0..6)line(c,left+(right-left)*i/6,priceTop,left+(right-left)*i/6,macBottom,Color.rgb(15,42,63))
        line(c,left,volBottom,right,volBottom,Color.rgb(28,56,77));line(c,left,zero,right,zero,Color.rgb(28,56,77))
        val step=(right-left)/points.size;fun xx(i:Int)=left+(i+.5f)*step
        points.forEachIndexed {i,a ->val b=a.bar;val col=if(b.close>=b.open)pink else mint;line(c,xx(i),py(b.high),xx(i),py(b.low),col,1.3f)
            p.color=col;c.drawRect(xx(i)-step*.28f,minOf(py(b.open),py(b.close)),xx(i)+step*.28f,maxOf(py(b.open),py(b.close))+1,p)
            c.drawRect(xx(i)-step*.3f,volBottom-(b.volumeShares/maxV*(volBottom-volTop)).toFloat(),xx(i)+step*.3f,volBottom,p)
            a.histogram?.let{p.color=if(it>=0)pink else mint;c.drawRect(xx(i)-step*.3f,minOf(zero,my(it)),xx(i)+step*.3f,maxOf(zero,my(it))+1,p)}
        }
        fun curve(color:Int,value:(ChartPoint)->Double?,sy:(Double)->Float){for(i in 1 until points.size){val a=value(points[i-1]);val b=value(points[i]);if(a!=null&&b!=null)line(c,xx(i-1),sy(a),xx(i),sy(b),color,1.5f)}}
        curve(gold,{it.ma5},::py);curve(blue,{it.ma10},::py);curve(Color.rgb(178,117,255),{it.ma20},::py)
        curve(blue,{it.dif},::my);curve(gold,{it.dea},::my)
        if(points.none{it.dif!=null})text(c,"MACD資料不足",left,macBottom,15f,muted)
        text(c,String.format(Locale.TAIWAN,"%.0f",maxV/1000),right+4,volTop+10,12f,muted,maxWidth=40f)
        text(c,"0",right+4,zero+4,12f,muted)
        c.restore()
    }
}
