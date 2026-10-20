package com.rex.mitakecompanion;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native compact OHLC + volume plot. No WebView per card. */
public final class MiniCandleView extends View {
    final String code;private final IntradayRepository repository;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private IntradayData data;private String status="五分K載入中…";private boolean loading;
    private final Runnable refresh=()->request();
    private final ViewTreeObserver.OnScrollChangedListener scroll=()->request();
    private final ViewTreeObserver.OnGlobalLayoutListener layout=()->request();
    private Runnable expand;
    MiniCandleView(Context c,String code,IntradayRepository repository){super(c);this.code=code;this.repository=repository;setContentDescription(code+" 五分K與成交量");setClickable(true);setOnClickListener(v->{if(data==null){repository.retry(code);request();}else if(expand!=null)expand.run();});}
    void expandWith(Runnable action){expand=action;}
    private float d(float x){return x*getResources().getDisplayMetrics().density;}
    private float textSize(float x){return x*getResources().getDisplayMetrics().scaledDensity;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getViewTreeObserver().addOnScrollChangedListener(scroll);getViewTreeObserver().addOnGlobalLayoutListener(layout);post(refresh);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);if(getViewTreeObserver().isAlive()){getViewTreeObserver().removeOnScrollChangedListener(scroll);getViewTreeObserver().removeOnGlobalLayoutListener(layout);}super.onDetachedFromWindow();}
    private void request(){
        removeCallbacks(refresh);if(!isAttachedToWindow()||!isShown())return;
        Rect visible=new Rect();if(!getGlobalVisibleRect(visible)||visible.height()<d(24))return;
        if(!loading){loading=true;repository.load(code,(value,error)->{loading=false;if(value!=null){data=value;status="";}else status=error;invalidate();if(isAttachedToWindow())postDelayed(refresh,60000);});}
    }
    void setTestData(IntradayData value){data=value;status="";invalidate();}
    private void label(Canvas c,String text,float x,float y,int color,float size){paint.setColor(color);paint.setTextSize(textSize(size));paint.setStyle(Paint.Style.FILL);c.drawText(text,x,y,paint);}
    static String time(long seconds,String format){SimpleDateFormat f=new SimpleDateFormat(format,Locale.TAIWAN);f.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));return f.format(new Date(seconds*1000));}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);int muted=Color.rgb(143,163,186),fg=Color.rgb(238,244,251);
        float w=getWidth(),h=getHeight();canvas.drawColor(Color.rgb(10,20,32));
        label(canvas,"5 分 K  ·  Yahoo Finance",d(8),d(18),muted,10);
        if(data==null||data.bars.isEmpty()){label(canvas,status,d(8),h/2,muted,11);return;}
        List<IntradayData.Bar> bars=data.bars;IntradayData.Bar latest=bars.get(bars.size()-1);
        label(canvas,(status.isEmpty()?"":"更新失敗，保留 ")+time(latest.time,"MM/dd HH:mm")+" · 來源可能延遲",d(8),d(34),muted,9);
        float left=d(7),right=w-d(54),top=d(47),bottom=h-d(52),volBottom=h-d(21),volTop=bottom+d(9);
        if(right<=left||bottom<=top)return;
        double low=Double.MAX_VALUE,high=-Double.MAX_VALUE,volume=0;
        for(IntradayData.Bar b:bars){low=Math.min(low,b.low);high=Math.max(high,b.high);if(Double.isFinite(b.volume))volume=Math.max(volume,b.volume);}
        double spread=Math.max(high-low,Math.max(high*.001,.01)),min=low-spread*.08,max=high+spread*.08;
        for(int i=0;i<3;i++){
            double value=max-(max-min)*i/2.0;float y=top+(bottom-top)*i/2;
            paint.setColor(Color.rgb(33,49,65));paint.setStrokeWidth(d(.5f));canvas.drawLine(left,y,right,y,paint);
            label(canvas,String.format(Locale.US,"%.2f",value),right+d(3),y+d(3),muted,9);
        }
        float step=(right-left)/Math.max(1,bars.size()),body=Math.max(d(1),Math.min(d(8),step*.65f));
        for(int i=0;i<bars.size();i++){
            IntradayData.Bar b=bars.get(i);float x=left+(i+.5f)*step;
            int color=b.close>=b.open?Color.rgb(255,111,133):Color.rgb(101,230,196);
            float yh=(float)(top+(max-b.high)/(max-min)*(bottom-top)),yl=(float)(top+(max-b.low)/(max-min)*(bottom-top));
            float yo=(float)(top+(max-b.open)/(max-min)*(bottom-top)),yc=(float)(top+(max-b.close)/(max-min)*(bottom-top));
            paint.setColor(color);paint.setStrokeWidth(d(1));canvas.drawLine(x,yh,x,yl,paint);
            canvas.drawRect(x-body/2,Math.min(yo,yc),x+body/2,Math.max(Math.max(yo,yc),Math.min(yo,yc)+d(1)),paint);
            if(volume>0&&Double.isFinite(b.volume))canvas.drawRect(x-body/2,(float)(volBottom-b.volume/volume*(volBottom-volTop)),x+body/2,volBottom,paint);
        }
        label(canvas,time(bars.get(0).time,"HH:mm"),left,h-d(5),muted,9);
        label(canvas,time(latest.time,"HH:mm"),Math.max(left,right-d(35)),h-d(5),muted,9);
        label(canvas,"量（股）",right+d(3),volBottom,muted,8);
        setContentDescription(code+" 五分K，"+time(latest.time,"MM月dd日 HH:mm")+"，收盤 "+latest.close+"，來源可能延遲");
    }
}
