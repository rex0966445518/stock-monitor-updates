package com.rex.mitakecompanion;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;

/** Reused across snapshots so charts do not reload every three-second poll. */
public final class StockCard extends LinearLayout {
    private final MainActivity owner;private final String code;
    private final TextView name,score,price,gain,state,details;
    private final IntradayRepository repository;private JSONObject row;
    StockCard(MainActivity a,String code,IntradayRepository repository){
        super(a);owner=a;this.code=code;this.repository=repository;setOrientation(VERTICAL);setPadding(a.dp(16),a.dp(16),a.dp(16),a.dp(14));
        LinearLayout head=new LinearLayout(a);head.setGravity(Gravity.CENTER_VERTICAL);
        name=a.text("",18,MainActivity.TEXT,true);score=a.text("",24,MainActivity.TEAL,true);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));head.addView(score);a.add(this,head,8);
        LinearLayout numbers=new LinearLayout(a);price=a.text("",30,MainActivity.TEXT,true);gain=a.text("",18,MainActivity.TEAL,true);numbers.addView(price,new LinearLayout.LayoutParams(0,-2,1));numbers.addView(gain);a.add(this,numbers,4);
        details=a.text("",10,MainActivity.MUTED,false);a.add(this,details,7);
        state=a.text("",12,MainActivity.TEAL,true);a.add(this,state,10);
        if(IntradayData.validCode(code)){
            MiniCandleView chart=new MiniCandleView(a,code,repository);chart.expandWith(this::expand);addView(chart,new LinearLayout.LayoutParams(-1,a.dp(210)));
            TextView note=a.text("點圖放大 · 紅漲綠跌 · K圖行情與掃描股價時間可能不同",9,MainActivity.MUTED,false);a.add(this,note,6);
        }else a.add(this,a.text("股號待確認，暫不顯示K圖",11,MainActivity.MUTED,false),6);
        setOnClickListener(v->ChipKLauncher.open(a,code));
        a.add(this,a.text("點卡片開啟籌碼K線 App 主頁；個股可用複製股號搜尋",10,MainActivity.MUTED,false),6);
        LinearLayout actions=new LinearLayout(a);actions.addView(a.button("開啟籌碼K線",()->ChipKLauncher.open(a,code)),new LinearLayout.LayoutParams(0,-2,1));actions.addView(a.button("複製股號",()->ChipKLauncher.copy(a,code)),new LinearLayout.LayoutParams(0,-2,1));a.add(this,actions,6);
        TextView reason=a.button("查看判定原因",()->{if(row!=null)a.message(code+" · 判定原因",row.optString("reason")+"\n觀察訊號不代表一定漲停。");});a.add(this,reason,0);
    }
    void update(JSONObject r,boolean fresh){
        row=r;boolean demo=r.optBoolean("demo");int matched=r.optInt("matched"),accent=demo?MainActivity.GOLD:fresh?(matched==6?MainActivity.RED:MainActivity.TEAL):MainActivity.MUTED;
        String stockName=r.optString("name");name.setText((demo?"測試｜":"")+(stockName.isEmpty()?"股名待補":stockName)+"  "+code);
        score.setText(matched+"/6");score.setTextColor(accent);
        double p=r.optDouble("price",Double.NaN),change=r.optDouble("gain",Double.NaN);
        price.setText(Double.isFinite(p)&&p>0?String.format(Locale.US,"%.2f 元",p):"股價待確認");
        gain.setText(Double.isFinite(change)?String.format(Locale.US,"%+.2f%%",change):"—");gain.setTextColor(change>=0?MainActivity.RED:MainActivity.TEAL);
        details.setText(demo?"示範價格／漲跌，非即時行情 · "+String.format(Locale.TAIWAN,"%,d 張",r.optInt("volume")):"電腦掃描 "+owner.clock(r.optDouble("ts"))+" · "+String.format(Locale.TAIWAN,"%,d 張",r.optInt("volume"))+" · 曾達 "+r.optInt("peak")+"/6");
        state.setText(demo?"測試卡片 · 不列入真實訊號、不播報":!fresh?"非即時／待複查"+(matched<5?" · 已轉弱，保留紀錄":""):r.optBoolean("confirmed")?"必要檢核已通過":"觀察候選 · 必要檢核待確認");state.setTextColor(accent);
        setBackground(owner.box(MainActivity.PANEL,fresh?Color.rgb(42,71,79):Color.rgb(30,44,62),18));
    }
    private void expand(){
        Dialog dialog=new Dialog(owner);LinearLayout content=new LinearLayout(owner);content.setOrientation(VERTICAL);content.setPadding(owner.dp(12),owner.dp(12),owner.dp(12),owner.dp(12));content.setBackgroundColor(MainActivity.BG);
        owner.add(content,owner.text(name.getText()+" · 五分K",18,MainActivity.TEXT,true),12);
        MiniCandleView chart=new MiniCandleView(owner,code,repository);content.addView(chart,new LinearLayout.LayoutParams(-1,owner.dp(340)));
        owner.add(content,owner.text("K圖來源：Yahoo Finance，可能延遲。股價卡片來自電腦OCR，兩者時間各自標示。",11,MainActivity.MUTED,false),10);
        owner.add(content,owner.button("開啟 HiStock 五分K",()->{try{owner.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://histock.tw/stock/tchart.aspx?m=minks&no="+code+"&time=5")));}catch(RuntimeException e){owner.message("無法開啟來源","請安裝或啟用手機瀏覽器");}}),8);
        owner.add(content,owner.button("關閉",dialog::dismiss),0);dialog.setContentView(content);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-2);
    }
}
