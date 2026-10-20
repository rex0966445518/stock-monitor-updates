package com.rex.mitakecompanion.portfolio;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class PortfolioActivity extends Activity {
    private static final int BG=0xff0a101b,PANEL=0xff121d2d,TEXT=0xffeef4fb,MUTED=0xff8fa3ba,TEAL=0xff65e6c4,GOLD=0xfff5c870;
    private PortfolioStore store;private GeminiClient gemini;
    private LinearLayout content;private String selectedRun="",pickPage="";private boolean pickHolding,busy;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refreshCapture=new Runnable(){public void run(){if(!busy&&!selectedRun.isEmpty()&&ChipCaptureService.running())render();handler.postDelayed(this,2000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store=PortfolioStore.get(this);gemini=new GeminiClient(this);
        if(state!=null){selectedRun=state.getString("run","");pickPage=state.getString("page","");pickHolding=state.getBoolean("holding");}render();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("run",selectedRun);state.putString("page",pickPage);state.putBoolean("holding",pickHolding);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();if(content!=null)render();handler.post(refreshCapture);}
    @Override protected void onPause(){handler.removeCallbacks(refreshCapture);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy();}
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));t.setTextIsSelectable(true);return t;}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    void add(LinearLayout parent,View child){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(9);parent.addView(child,lp);}
    void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(TEAL);b.setAllCaps(false);b.setEnabled(!busy);b.setOnClickListener(v->action.run());add(parent,b);}
    LinearLayout panel(LinearLayout parent){LinearLayout v=column();v.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(16));v.setBackground(bg);add(parent,v);return v;}
    String time(long ms){return new SimpleDateFormat("MM/dd HH:mm",Locale.TAIWAN).format(new Date(ms));}
    void message(String title,String value){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("確定",null).show();}
    void render(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);content=column();content.setPadding(dp(20),dp(24),dp(20),dp(30));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,in)->{int t,b;if(Build.VERSION.SDK_INT>=30){Insets x=in.getInsets(WindowInsets.Type.systemBars());t=x.top;b=x.bottom;}else{t=in.getSystemWindowInsetTop();b=in.getSystemWindowInsetBottom();}content.setPadding(dp(20),t+dp(18),dp(20),b+dp(24));return in;});
        add(content,text("個人股倉分析",25,TEXT));
        if(busy){add(content,text("正在處理，請稍候…",15,GOLD));add(content,new ProgressBar(this));return;}
        try{if(selectedRun.isEmpty())home();else details(store.run(selectedRun));}catch(Exception e){message("資料讀取失敗",safe(e));button(content,"回到股倉",()->{selectedRun="";render();});}
    }
    void home()throws Exception{
        add(content,text("匯入持股截圖 → 核對 → 收集六類圖片 → Gemini分析",13,MUTED));
        button(content,"匯入持股截圖",()->pick(true,""));button(content,"手動新增持股",()->editHolding(null));button(content,"Gemini設定",this::settings);
        add(content,text("截圖先在本機辨識；只有啟動Gemini辨識、送出分析或勾選本次自動分析才上傳Google。持股價格是截圖紀錄，並非即時行情。",12,GOLD));
        JSONArray holdings=store.read().getJSONArray("holdings");add(content,text("我的持股 · "+holdings.length()+" 檔",19,TEXT));
        if(holdings.length()==0)add(content,text("尚無持股，請匯入截圖或手動新增。",14,MUTED));
        for(int i=0;i<holdings.length();i++){
            JSONObject h=holdings.getJSONObject(i);LinearLayout p=panel(content);
            TextView title=text(h.getString("name")+"  "+h.getString("code")+"  ›",19,TEAL);title.setTextIsSelectable(false);title.setContentDescription("開啟並收集 "+h.getString("code"));title.setOnClickListener(view->{try{selectedRun=store.newRun(h).getString("id");render();beginCapture(store.run(selectedRun));}catch(Exception e){message("無法建立收集",safe(e));}});add(p,title);
            add(p,text(h.getString("shares")+" 股 · 平均成本 "+shown(h,"cost")+"\n紀錄現價 "+shown(h,"price")+" · "+PortfolioStore.pnl(h),13,TEXT));
            add(p,text("核對時間 "+time(h.optLong("confirmed")),11,MUTED));
            button(p,"收集圖片與分析",()->{try{selectedRun=store.newRun(h).getString("id");render();}catch(Exception e){message("無法建立分析",safe(e));}});
            button(p,"修改持股",()->editHolding(h));button(p,"刪除此持股",()->new AlertDialog.Builder(this).setTitle("刪除持股？").setMessage("分析歷史仍保留，可在各紀錄中另外刪除。").setPositiveButton("刪除",(d,w)->{try{store.deleteHolding(h.optString("code"));render();}catch(Exception e){message("刪除失敗",safe(e));}}).setNegativeButton("取消",null).show());
        }
        add(content,text("分析歷史",19,TEXT));JSONArray runs=store.read().getJSONArray("runs");
        for(int i=runs.length()-1;i>=0;i--){JSONObject r=runs.getJSONObject(i),h=r.getJSONObject("holding");button(content,h.optString("code")+" "+h.optString("name")+" · "+time(r.optLong("created"))+"\n"+r.optString("status"),()->{selectedRun=r.optString("id");render();});}
        button(content,"返回漲停雷達",this::finish);
    }
    String shown(JSONObject o,String k){return o.optString(k).isEmpty()?"待核對":o.optString(k);}
    void editHolding(JSONObject previous){
        LinearLayout fields=column();fields.setPadding(dp(18),0,dp(18),0);List<EditText> entries=new ArrayList<>();String[] names={"股號","股名","股數（1張=1000股）","平均成本／股（可留空）","截圖現價／股（可留空）"},keys={"code","name","shares","cost","price"};
        for(int i=0;i<keys.length;i++){EditText e=new EditText(this);e.setHint(names[i]);e.setText(previous==null?"":previous.optString(keys[i]));e.setSingleLine(true);if(i>=2)e.setInputType(i==2?2:8194);fields.addView(e);entries.add(e);}
        add(fields,text("請對照原圖核對。股數單位固定為股；資料儲存後才能開始個股分析。",12,GOLD));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("核對持股資料").setView(fields).setPositiveButton("確認儲存",null).setNegativeButton("取消",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{try{
            JSONObject h=PortfolioStore.holding(entries.get(0).getText().toString(),entries.get(1).getText().toString(),entries.get(2).getText().toString(),entries.get(3).getText().toString(),entries.get(4).getText().toString()).put("confirmed",System.currentTimeMillis());
            String old=previous==null?"":previous.optString("code");JSONArray all=store.read().getJSONArray("holdings");boolean exists=false;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).optString("code").equals(h.getString("code")))exists=true;
            if(exists&&!old.equals(h.getString("code"))){message("股號已存在","請從原持股卡片修改，避免覆蓋既有資料。");return;}
            store.saveHolding(h);if(!old.isEmpty()&&!old.equals(h.getString("code")))store.deleteHolding(old);dialog.dismiss();render();
        }catch(Exception e){message("請核對欄位",safe(e));}});
    }
    void settings(){
        LinearLayout fields=column();fields.setPadding(dp(18),0,dp(18),0);
        add(fields,text("使用你自己的Google Gemini API金鑰。圖片與持股資料會送至Google，依你的API帳戶額度計費。金鑰在本機加密儲存，請勿貼到聊天。",13,TEXT));
        EditText key=new EditText(this);key.setHint(gemini.hasKey()?"已設定；留空保留原金鑰":"輸入Gemini API金鑰");key.setInputType(129);key.setSaveEnabled(false);fields.addView(key);
        EditText model=new EditText(this);model.setSingleLine(true);model.setHint("模型名稱");model.setText(gemini.model());fields.addView(model);
        button(fields,"取得API金鑰（Google AI Studio）",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://aistudio.google.com/apikey")));}catch(Exception e){message("無法開啟Google AI Studio","請用瀏覽器開啟 aistudio.google.com/apikey 取得自己的Gemini API金鑰。");}});
        new AlertDialog.Builder(this).setTitle("Gemini設定").setView(fields).setPositiveButton("儲存",(d,w)->{try{gemini.model(model.getText().toString().trim());if(!key.getText().toString().trim().isEmpty())gemini.saveKey(key.getText().toString());key.setText("");message("設定已儲存","尚未傳送任何圖片。分析時會使用所選模型。");}catch(Exception e){message("設定失敗",safe(e));}}).setNeutralButton("清除金鑰",(d,w)->{try{gemini.saveKey("");}catch(Exception e){message("清除失敗",safe(e));}}).setNegativeButton("取消",null).show();
    }
    void pick(boolean holding,String page){pickHolding=holding;pickPage=page;try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,41);}catch(Exception e){message("無法開啟相簿",safe(e));}}
    interface Job{Object run()throws Exception;} interface Done{void accept(Object result)throws Exception;}
    void background(Job job,Done done){busy=true;render();worker.execute(()->{Object result=null;Exception failure=null;try{result=job.run();}catch(Exception e){failure=e;}Object value=result;Exception error=failure;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;render();if(error!=null)message("未完成",safe(error));else try{done.accept(value);}catch(Exception e){message("未完成",safe(e));}});});}
    static String safe(Exception e){if(e instanceof java.net.SocketTimeoutException)return "連線逾時，請檢查網路後重試；未自動重送。";if(e instanceof org.json.JSONException)return "資料格式無效或辨識回覆不完整，未寫入持股。";String s=e.getMessage();return s!=null&&s.length()<240?s:"處理失敗，請重新選取圖片或檢查設定（"+e.getClass().getSimpleName()+"）";}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();boolean holding=pickHolding;String page=pickPage,id=selectedRun;
        background(()->{
            File file=holding?new File(getCacheDir(),"holding-"+UUID.randomUUID()+".jpg"):new File(getCacheDir(),"page-"+UUID.randomUUID()+".jpg");
            PortfolioImages.importImage(this,uri,file);String ocr;
            try{ocr=PortfolioImages.recognize(file).getText();}catch(Exception e){ocr="本機文字辨識失敗，請人工核對圖片";}
            return new String[]{file.getAbsolutePath(),ocr};
        },value->{String[] pair=(String[])value;File f=new File(pair[0]);if(holding)reviewHoldingImage(f,pair[1]);else reviewPage(id,page,f,pair[1]);});
    }
    ImageView preview(File f){ImageView image=new ImageView(this);BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=2;image.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath(),opts));image.setAdjustViewBounds(true);image.setMaxHeight(dp(480));image.setContentDescription("匯入圖片預覽");return image;}
    void reviewHoldingImage(File f,String ocr){
        LinearLayout v=column();add(v,preview(f));add(v,text("本機辨識文字，請對照原圖：\n"+ocr,13,TEXT));
        button(v,"Gemini辨識持股欄位",()->{
            if(!gemini.hasKey()){settings();return;}
            new AlertDialog.Builder(this).setTitle("傳送這張持股截圖給Google？").setMessage("圖片可能含帳號、姓名與持股金額。請先在相簿遮蔽不需要的個資。將使用你的Gemini API額度，辨識結果須核對後才儲存。").setPositiveButton("傳送並辨識",(d,w)->background(()->PortfolioStore.parseExtract(gemini.generate(GeminiClient.EXTRACT,Collections.singletonList(f),Collections.singletonList("持股截圖"),true)),value->reviewDrafts((JSONArray)value))).setNegativeButton("取消",null).show();
        });button(v,"手動核對並新增",()->editHolding(null));
        ScrollView scroll=new ScrollView(this);scroll.addView(v);new AlertDialog.Builder(this).setTitle("持股截圖辨識").setView(scroll).setNegativeButton("關閉",(d,w)->f.delete()).show();
    }
    void reviewDrafts(JSONArray drafts){LinearLayout v=column();add(v,text("辨識結果尚未存入股倉。請逐筆按核對，確認股數單位、成本及股價。",13,GOLD));
        for(int i=0;i<drafts.length();i++){JSONObject h=drafts.optJSONObject(i);button(v,"核對 "+h.optString("code")+" "+h.optString("name")+"\n"+shown(h,"shares")+"股 / 成本 "+shown(h,"cost"),()->editHolding(h));}
        ScrollView scroll=new ScrollView(this);scroll.addView(v);new AlertDialog.Builder(this).setTitle("持股辨識結果").setView(scroll).setNegativeButton("完成",null).show();}
    void reviewPage(String id,String page,File f,String ocr)throws Exception{
        JSONObject h=store.run(id).getJSONObject("holding");LinearLayout v=column();add(v,preview(f));
        add(v,text("預期："+h.optString("code")+" "+h.optString("name")+" · "+page+"\n請核對圖片股號、頁面、日期與週期。\n\n本機辨識：\n"+ocr,13,TEXT));
        ScrollView scroll=new ScrollView(this);scroll.addView(v);
        new AlertDialog.Builder(this).setTitle("核對個股圖片").setView(scroll).setPositiveButton("已核對，加入本次分析",(d,w)->{try{File target=store.imageFile(id,page);java.nio.file.Files.move(f.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);store.attach(id,page,target,"使用者匯入並人工核對",ocr);render();}catch(Exception e){message("圖片儲存失敗",safe(e));}}).setNegativeButton("取消",(d,w)->f.delete()).show();
    }
    void details(JSONObject r)throws Exception{
        JSONObject h=r.getJSONObject("holding"),pages=r.getJSONObject("pages");
        add(content,text(h.optString("name")+"  "+h.optString("code"),22,TEAL));add(content,text(h.optString("shares")+" 股 · 成本 "+shown(h,"cost")+"\n建立 "+time(r.optLong("created"))+" · "+r.optString("status"),13,TEXT));
        add(content,text("每次分析使用獨立圖片組。請確認六頁屬於同一檔股票及相容日期；匯入時間不代表行情日期。",12,GOLD));
        boolean running=ChipCaptureService.running();
        if(running)button(content,"停止本次自動收集",()->{ChipCaptureService.cancel();render();});
        else button(content,"開啟籌碼K線並嘗試收集六頁",()->beginCapture(r));
        add(content,text("自動收集測試版：需Android 14以上及你手動開啟輔助使用權限。只辨識明確可讀的股號與頁籤，無法確認就停止。尚未取得你的操作影片，實機適配未驗證；可逐頁匯入截圖完成分析。",12,MUTED));
        for(String page:PortfolioStore.PAGES){LinearLayout p=panel(content);JSONObject image=pages.optJSONObject(page);add(p,text(page+(image==null?" · 缺圖":" · 已加入"),17,image==null?GOLD:TEAL));
            if(image!=null){add(p,preview(new File(image.getString("path"))));add(p,text("儲存 "+time(image.optLong("saved"))+" · "+image.optString("source"),11,MUTED));}
            if(!running)button(p,image==null?"匯入「"+page+"」截圖":"更換「"+page+"」截圖",()->pick(false,page));
        }
        if(!running)button(content,"送至Gemini分析",()->confirmAnalysis(r));
        if(r.has("analysis")){add(content,text("Gemini分析 · "+time(r.optLong("analyzed")),18,TEAL));add(content,text("AI根據圖片判讀，可能有誤。請核對來源數據；結果不是自動交易指令。",12,GOLD));add(content,text(r.getString("analysis"),15,TEXT));}
        button(content,"返回股倉",()->{selectedRun="";render();});
        if(!running)button(content,"刪除此分析與圖片",()->new AlertDialog.Builder(this).setTitle("刪除此分析？").setMessage("將刪除本機六類圖片與分析結果。").setPositiveButton("刪除",(d,w)->{try{store.deleteRun(r.optString("id"));selectedRun="";render();}catch(Exception e){message("刪除失敗",safe(e));}}).setNegativeButton("取消",null).show());
    }
    void beginCapture(JSONObject r){
        if(Build.VERSION.SDK_INT<34){message("請改用圖片匯入","這版跨App擷取需要Android 14以上；仍可手動匯入六類截圖分析。");return;}
        LinearLayout options=column();options.setPadding(dp(18),0,dp(18),0);CheckBox automatic=new CheckBox(this);automatic.setText("六頁完成後自動傳送Google Gemini分析（使用我的API額度）");automatic.setChecked(false);automatic.setEnabled(gemini.hasKey());options.addView(automatic);
        if(!gemini.hasKey())add(options,text("尚未設定Gemini金鑰；本次只收集圖片。可稍後在股倉設定金鑰並送出。",12,GOLD));
        new AlertDialog.Builder(this).setTitle("啟動單次籌碼K線收集").setMessage("本次目標："+r.optJSONObject("holding").optString("code")+"。\n\n會在籌碼K線中嘗試輸入股號、選擇個股，再依序切換K線、籌碼日報、法人、多空、主力、大戶並截圖。只讀取籌碼K線，不操作買賣；找不到股號或頁籤就停止。\n\n若勾選自動分析，六頁全部核對成功後會立即將圖片、股數及成本傳送Google，不再另行確認。否則先保存本機，返回檢查後手動送出。\n\n輔助使用權限須由你在Android設定手動啟用；若限制側載App設定，需在應用程式資訊允許受限制的設定。").setView(options).setPositiveButton(ChipCaptureService.available()?"依上述選項開始":"前往輔助使用設定",(d,w)->{
            if(!ChipCaptureService.available()){try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(Exception e){message("無法開啟設定",safe(e));}return;}
            try{JSONObject fresh=store.run(r.getString("id"));fresh.put("auto_analyze",automatic.isChecked());store.updateRun(fresh);ChipCaptureService.begin(r.getString("id"),r.getJSONObject("holding").getString("code"));}catch(Exception e){message("無法開始收集",safe(e));}
        }).setNegativeButton("取消",null).show();
    }
    void confirmAnalysis(JSONObject original){
        if(!gemini.hasKey()){settings();return;}
        try{JSONObject r=store.run(original.getString("id"));JSONObject pages=r.getJSONObject("pages");if(pages.length()==0){message("尚未加入圖片","請先匯入或收集至少一類個股圖片。");return;}
            List<File> images=new ArrayList<>();List<String> labels=new ArrayList<>();StringBuilder missing=new StringBuilder();
            for(String p:PortfolioStore.PAGES){JSONObject v=pages.optJSONObject(p);if(v==null)missing.append(p).append("、");else{images.add(new File(v.getString("path")));labels.add(p);}}
            new AlertDialog.Builder(this).setTitle("送出 "+images.size()+" 張圖片至Google Gemini？").setMessage("個股 "+r.getJSONObject("holding").getString("code")+"\n包含已核對股數與成本。\n"+(missing.length()>0?"缺圖："+missing+"，只進行部分分析。\n":"")+"請先核對所有圖片與日期，移除姓名、帳號等不需上傳的內容。使用你的API額度；完成後結果保存在本機。").setPositiveButton("確認圖片並送出分析",(d,w)->background(()->{
                return PortfolioAnalysis.analyze(this,r.getString("id"),false);
            },v->render())).setNegativeButton("取消",null).show();
        }catch(Exception e){message("無法分析",safe(e));}
    }
}
