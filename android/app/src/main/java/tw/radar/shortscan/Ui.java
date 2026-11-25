package tw.radar.shortscan;
import android.content.Context;import android.graphics.Color;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.view.View;import android.widget.*;import org.json.*;import java.util.Locale;
final class Ui {
 static final int BG=Color.rgb(16,24,32),PANEL=Color.rgb(26,40,50),MINT=Color.rgb(85,217,173),GOLD=Color.rgb(246,200,110),RED=Color.rgb(255,105,110),FG=Color.rgb(238,243,245),MUTED=Color.rgb(153,174,187);
 static final String[] LABELS={"連3根高低點下降","均價反壓","帶量破底","弱於大盤","5分 MACD 轉弱"};
 static int dp(Context c,float v){return (int)(v*c.getResources().getDisplayMetrics().density+.5f);}
 static GradientDrawable bg(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);return g;}
 static TextView text(Context c,String s,int size,int color,boolean bold){TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,dp(c,4),0,dp(c,4));return v;}
 static LinearLayout col(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(1);return l;}
 static LinearLayout card(Context c){LinearLayout l=col(c);l.setBackground(bg(PANEL,dp(c,14)));int p=dp(c,14);l.setPadding(p,p,p,p);return l;}
 static void add(LinearLayout root,View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(root.getContext(),10);root.addView(v,p);}
 static Button button(Context c,String s){Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setTextColor(FG);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(44,65,77)));return b;}
 static String num(JSONObject o,String k){return o.isNull(k)?"—":String.format(Locale.TAIWAN,"%.2f",o.optDouble(k));}
 static String yes(JSONObject o,String k){return o.isNull(k)?"未確認":o.optBoolean(k)?"可":"不可";}
 static String borrow(JSONObject o){return o.isNull("borrow_lots")?"未接券商／未知":o.optInt("borrow_lots")+" 張";}
 static String reasons(JSONObject j){JSONArray a=j.optJSONArray("conditions");StringBuilder b=new StringBuilder();if(a!=null)for(int i=0;i<5;i++)if(a.optBoolean(i))b.append(b.length()==0?"":"  ·  ").append(LABELS[i]);return b.toString();}
 static JSONObject sample(){try{return new JSONObject("{\"type\":\"signal\",\"demo\":true,\"symbol\":\"2454\",\"name\":\"聯發科\",\"price\":1250,\"hits\":5,\"conditions\":[true,true,true,true,true],\"vwap\":1258.2,\"stop_a\":1262,\"stop_b\":1261.9746,\"daytrade\":true,\"below_flat\":true,\"borrow_lots\":null,\"qualification_source\":\"模擬資料\"}");}catch(Exception e){return new JSONObject();}}
}
