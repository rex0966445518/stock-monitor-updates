package com.rex.mitakecompanion;
import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
public class MainActivity extends Activity {
    static final int BG=Color.rgb(10,16,27),PANEL=Color.rgb(18,29,45),TEXT=Color.rgb(238,244,251),MUTED=Color.rgb(143,163,186),TEAL=Color.rgb(101,230,196),RED=Color.rgb(255,111,133),GOLD=Color.rgb(245,200,112);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,StockCard> stockCards=new HashMap<>();
    private final IntradayRepository intraday=new IntradayRepository();
    private AppUpdater updater;private SpeechAnnouncer testSpeech;
    LinearLayout content,cards;TextView connection,counts6,counts5,freshness;String filter="全部",last="";
    private final Runnable tick=new Runnable(){public void run(){refresh();handler.postDelayed(this,1000);}};
    int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    GradientDrawable box(int color,int stroke,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(radius));if(stroke!=0)b.setStroke(dp(1),stroke);return b;}
    TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    void add(LinearLayout parent,View child,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);parent.addView(child,p);}
    TextView button(String title,Runnable action){TextView b=text(title,14,TEAL,true);b.setGravity(Gravity.CENTER);b.setPadding(dp(14),dp(11),dp(14),dp(11));b.setBackground(box(PANEL,Color.rgb(40,62,79),12));b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        updater=new AppUpdater(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(24),dp(22),dp(28));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,in)->{int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=in.getInsets(WindowInsets.Type.systemBars());top=i.top;bottom=i.bottom;}else{top=in.getSystemWindowInsetTop();bottom=in.getSystemWindowInsetBottom();}content.setPadding(dp(22),top+dp(20),dp(22),bottom+dp(24));return in;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("◉  漲停雷達",27,TEXT,true);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));header.addView(button("配對",this::showPairing));add(content,header,8);
        add(content,text("MITAKE LIVE  /  雷允澤製 v0.1.4",11,MUTED,false),22);
        LinearLayout tools=new LinearLayout(this);
        tools.addView(button("一鍵更新",()->updater.check()),new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(button("語音通報",this::showVoice),new LinearLayout.LayoutParams(0,-2,1));tools.addView(button("連線診斷",this::showDiagnosis),new LinearLayout.LayoutParams(0,-2,1));add(content,tools,10);
        connection=text("●  尚未連線",14,TEAL,true);add(content,connection,6);
        freshness=text("由電腦持續掃描，手機同步觀察候選",12,MUTED,false);add(content,freshness,20);
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.HORIZONTAL);hero.setPadding(dp(20),dp(20),dp(20),dp(20));hero.setBackground(box(PANEL,Color.rgb(33,56,72),20));
        LinearLayout six=new LinearLayout(this);six.setOrientation(LinearLayout.VERTICAL);six.addView(text("6 / 6 基礎條件",13,RED,true));counts6=text("—",44,TEXT,true);six.addView(counts6);six.addView(text("必要檢核另行標示",11,MUTED,false));hero.addView(six,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout five=new LinearLayout(this);five.setOrientation(LinearLayout.VERTICAL);five.addView(text("5 / 6 接近候選",13,TEAL,true));counts5=text("—",44,TEXT,true);five.addView(counts5);five.addView(text("持續追蹤變化",11,MUTED,false));hero.addView(five,new LinearLayout.LayoutParams(0,-2,1));add(content,hero,20);
        LinearLayout tabs=new LinearLayout(this);for(String f:new String[]{"全部","6/6","5/6"}){TextView tab=button(f,()->{filter=f;last="";refresh();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(6);tabs.addView(tab,p);}add(content,tabs,18);
        add(content,text("今日訊號",18,TEXT,true),4);add(content,text("符合數優先排列 · 轉弱紀錄保留",12,MUTED,false),14);
        cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);add(content,cards,14);
        add(content,button("掃描電腦配對碼",this::scan),10);
        add(content,button("停止手機連線",()->{BridgeService.prefs(this).edit().putBoolean("enabled",false).apply();stopService(new Intent(this,BridgeService.class));last="";refresh();}),16);
        add(content,text("5/6 與 6/6 是觀察條件，並非買進指令。\n價格來自電腦 OCR；請在下單前核對原始行情。",11,MUTED,false),4);
        if(getIntent().getData()!=null)acceptPair(getIntent().getData().toString());
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getData()!=null)acceptPair(intent.getData().toString());}
    @Override public void onResume(){super.onResume();handler.post(tick);if(updater!=null)updater.resume();}
    @Override public void onPause(){handler.removeCallbacks(tick);if(updater!=null)updater.pause();super.onPause();}
    static final int CAMERA_REQUEST=11;
    void scan(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
            return;
        }
        launchScanner();
    }
    void launchScanner(){
        try{
            new IntentIntegrator(this).setDesiredBarcodeFormats(Collections.singletonList(IntentIntegrator.QR_CODE)).setPrompt("掃描電腦「手機連動／配對」的QR碼").setBeepEnabled(false).setOrientationLocked(false).initiateScan();
        }catch(RuntimeException | LinkageError e){
            message("無法開啟掃描", "請改在「配對」輸入電腦位址與配對碼，或貼上完整配對連結。\n錯誤類型："+e.getClass().getSimpleName());
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==CAMERA_REQUEST){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)launchScanner();
            else message("相機權限未開啟","請到系統設定允許相機權限，或在「配對」手動輸入電腦位址與配對碼。");
        }
    }
    @Override public void onActivityResult(int request,int result,Intent data){IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);if(scan!=null){if(scan.getContents()!=null)acceptPair(scan.getContents());return;}super.onActivityResult(request,result,data);}
    void acceptPair(String value){try{Pairing pair=Pairing.parse(value);new AlertDialog.Builder(this).setTitle("連接這台電腦？").setMessage(pair.address+"\n接收5/6、6/6觀察通知，直到手動停止。").setPositiveButton("連線",(d,w)->connect(pair)).setNegativeButton("取消",null).show();}catch(Exception e){message("配對失敗",e.getMessage());}}
    void connect(Pairing pair){stopService(new Intent(this,BridgeService.class));BridgeService.latest=null;BridgeService.receivedAt=0;BridgeService.connection="正在連接電腦…";
        BridgeService.prefs(this).edit().putString("address",pair.address).putString("token",pair.token).putString("session","").putLong("seq",0).remove("snapshot").putBoolean("enabled",true).apply();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
        startForegroundService(new Intent(this,BridgeService.class));last="";refresh();
    }
    void showPairing(){LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(20),dp(8),dp(20),0);
        EditText address=new EditText(this);address.setText(BridgeService.prefs(this).getString("address",""));address.setHint("電腦位址，例如 http://192.168.1.10:8765");address.setInputType(17);fields.addView(address);
        EditText token=new EditText(this);token.setHint("配對碼，或貼上完整 mitake://pair 連結");token.setInputType(129);fields.addView(token);
        TextView hint=text("同Wi-Fi可直接連線；外出請經私人VPN連回電腦。\n電腦須保持開機、啟用連線並執行掃描。",12,MUTED,false);fields.addView(hint);
        new AlertDialog.Builder(this).setTitle("電腦連動").setView(fields).setPositiveButton("連線",(d,w)->{try{String value=token.getText().toString().trim();if(value.startsWith("mitake:"))acceptPair(value);else connect(new Pairing(address.getText().toString(),value));}catch(Exception e){message("無法連線",e.getMessage());}}).setNeutralButton("掃描QR碼",(d,w)->scan()).setNegativeButton("取消",null).show();
    }
    @Override public void onDestroy(){intraday.close();stockCards.clear();if(updater!=null)updater.close();if(testSpeech!=null)testSpeech.close();super.onDestroy();}
    void showDiagnosis(){
        String address=BridgeService.prefs(this).getString("address","");
        long success=BridgeService.prefs(this).getLong("last_success",0);
        String text="電腦位址："+(address.isEmpty()?"尚未配對":address)+"\n\n"+ConnectionDiagnosis.network(this)+"\n\n最後成功連線："+(success==0?"尚無成功紀錄":new SimpleDateFormat("MM/dd HH:mm:ss",Locale.TAIWAN).format(new Date(success)))+"\n\n"+(BridgeService.connectionReason.isEmpty()?BridgeService.connection:BridgeService.connectionReason)+"\n\n電腦需開啟「進階工具 → 手機連動／配對 → 啟用／顯示配對碼」。Windows 防火牆需允許私人網路。電腦換 IP 或重設配對碼後，請重新掃碼。";
        new AlertDialog.Builder(this).setTitle("連線診斷").setMessage(text).setPositiveButton("立即重連",(d,w)->{if(BridgeService.prefs(this).getBoolean("enabled",false))startForegroundService(new Intent(this,BridgeService.class).setAction(BridgeService.RETRY));else showPairing();}).setNegativeButton("關閉",null).show();
    }
    void showVoice(){
        LinearLayout settings=new LinearLayout(this);settings.setOrientation(LinearLayout.VERTICAL);settings.setPadding(dp(20),dp(8),dp(20),dp(8));
        Switch enabled=new Switch(this);enabled.setText("新訊號語音通報");enabled.setChecked(BridgeService.prefs(this).getBoolean("voice_enabled",true));enabled.setOnCheckedChangeListener((v,on)->BridgeService.prefs(this).edit().putBoolean("voice_enabled",on).apply());settings.addView(enabled);
        TextView status=text(BridgeService.prefs(this).getString("voice_status","連線後啟用中文語音"),12,MUTED,false);settings.addView(status);
        settings.addView(text("播報股名、股號、5/6 或 6/6 及檢核狀態。只播報有效新訊號；重播、過期及暫停資料不播報。音量跟隨手機媒體音量。",12,MUTED,false));
        new AlertDialog.Builder(this).setTitle("語音通報設定").setView(settings).setPositiveButton("試聽",(d,w)->{if(testSpeech==null)testSpeech=new SpeechAnnouncer(this);testSpeech.test();}).setNeutralButton("系統語音設定",(d,w)->{try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(RuntimeException e){message("語音設定","請從手機系統設定開啟文字轉語音，下載中文語音。");}}).setNegativeButton("完成",null).show();
    }
    void message(String title,String value){new AlertDialog.Builder(this).setTitle(title).setMessage(value==null?"請重新掃描配對碼":value).setPositiveButton("確定",null).show();}
    String clock(double seconds){return new SimpleDateFormat("HH:mm:ss",Locale.TAIWAN).format(new Date((long)(seconds*1000)));}
    void refresh(){JSONObject data=BridgeService.latest;boolean cached=false;
        if(data==null)try{data=new JSONObject(BridgeService.prefs(this).getString("snapshot",""));cached=true;}catch(Exception ignored){}
        boolean enabled=BridgeService.prefs(this).getBoolean("enabled",false);
        boolean online=enabled&&!cached&&BridgeService.receivedAt>0&&SystemClock.elapsedRealtime()-BridgeService.receivedAt<10000;
        String label=enabled?BridgeService.connection:"已停止連線";
        connection.setText("●  "+label);connection.setTextColor(online?TEAL:GOLD);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)label+="｜通知未允許，僅顯示列表";
        freshness.setText((enabled&&!online?ConnectionDiagnosis.network(this)+"\n":"")+(data==null?"等待配對電腦資料":(online?"資料更新 ":"最後快照 ")+clock(data.optDouble("server_time"))+" · "+(online?data.optString("status"):"目前非即時"))+ (label.contains("通知未允許")?"\n通知未允許，請到系統設定開啟":""));
        String key=(data==null?"":data.toString())+filter+online+(data!=null&&online?(long)((data.optDouble("server_time")+(SystemClock.elapsedRealtime()-BridgeService.receivedAt)/1000.0)/15):0);
        if(key.equals(last))return;last=key;cards.removeAllViews();int n5=0,n6=0;
        JSONArray rows=data==null?null:data.optJSONArray("rows");
        double now=data==null?0:data.optDouble("server_time")+(online?(SystemClock.elapsedRealtime()-BridgeService.receivedAt)/1000.0:0);
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;
            boolean fresh=online&&data.optBoolean("active")&&r.optBoolean("valid")&&now-r.optDouble("ts")<=15&&now>=r.optDouble("ts");
            int count=r.optInt("matched");if(count<5&&r.optInt("peak")<5)continue;if(fresh&&count==6)n6++;if(fresh&&count==5)n5++;
            if(filter.equals("6/6")&&count!=6||filter.equals("5/6")&&count!=5)continue;
            addCard(r,fresh);
        }
        Set<String> present=new HashSet<>();if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);if(r!=null)present.add(r.optString("code"));}stockCards.keySet().retainAll(present);
        counts6.setText(String.valueOf(n6));counts5.setText(String.valueOf(n5));
        if(cards.getChildCount()==0){TextView empty=text(data==null?"等待與電腦配對\n\n在電腦開啟「進階工具 → 手機連動／配對」\n啟用連線後，掃描QR碼即可。":"尚無此類候選\n\n電腦需開啟全部六項條件並持續掃描。\n找到候選後會自動出現在這裡。",15,MUTED,false);empty.setPadding(dp(18),dp(28),dp(18),dp(28));empty.setBackground(box(PANEL,0,16));add(cards,empty,12);}
    }
    void addCard(JSONObject r,boolean fresh){
        String code=r.optString("code");
        StockCard card=stockCards.get(code);
        if(card==null){card=new StockCard(this,code,intraday);stockCards.put(code,card);}
        card.update(r,fresh);add(cards,card,12);
    }
}
