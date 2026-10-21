package com.rex.mitakecompanion.portfolio;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

/** First-use setup, then a card tap starts one bounded six-page capture and upload. */
public final class CandidateCaptureActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private JSONObject candidate;private String runId="",signature="";private PortfolioStore store;
    private boolean starting;private ScrollView scroll;
    private static final String CONSENT="candidate_auto_ai_images";
    private final Runnable tick=new Runnable(){public void run(){maybeStart();render();handler.postDelayed(this,1200);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);store=PortfolioStore.get(this);
        try{loadTarget(getIntent());if(state!=null)runId=state.getString("run","");render();}
        catch(Exception e){candidate=null;new AlertDialog.Builder(this).setTitle("無法啟動卡片分析").setMessage("候選資料無效，請返回雷達重新點擊真實5/6或6/6卡片。").setPositiveButton("返回",(d,w)->finish()).setOnCancelListener(d->finish()).show();}
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("run",runId);super.onSaveInstanceState(state);}
    @Override public void onResume(){super.onResume();if(candidate!=null){handler.post(tick);}}
    @Override public void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy();}
    private void loadTarget(Intent intent)throws Exception{
        if(intent.hasExtra(CandidateCapture.RUN)){runId=intent.getStringExtra(CandidateCapture.RUN);candidate=store.run(runId).getJSONObject("candidate");}
        else candidate=new JSONObject(intent.getStringExtra(CandidateCapture.EXTRA));CandidateCapture.target(candidate);
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);try{loadTarget(intent);signature="";render();}catch(Exception e){show("無法顯示查詢",PortfolioActivity.safe(e));}}
    private boolean allowed(){return getSharedPreferences("portfolio_settings",0).getBoolean(CONSENT,false);}
    private boolean installed(){return getPackageManager().getLaunchIntentForPackage("com.cmoney.chipk")!=null;}
    private boolean ready(){return Build.VERSION.SDK_INT>=34&&installed()&&ChipCaptureService.available()&&getPackageManager().getLaunchIntentForPackage(GoogleSearchCapture.CHROME)!=null;}
    private void maybeStart(){
        if(starting||!runId.isEmpty()||!allowed()||!ready()||ChipCaptureService.running())return;
        starting=true;
        try{JSONObject run=CandidateCapture.newRun(store,candidate);runId=run.getString("id");ChipCaptureService.begin(runId,candidate.getString("code"));}
        catch(Exception e){try{if(!runId.isEmpty())store.status(runId,"未啟動："+PortfolioActivity.safe(e));}catch(Exception ignored){}show("無法開始收集",PortfolioActivity.safe(e));}
        finally{starting=false;signature="";}
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void text(LinearLayout v,String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(7),0,dp(7));t.setTextIsSelectable(true);v.addView(t);}
    private void button(LinearLayout v,String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(w->action.run());v.addView(b);}
    private void show(String title,String body){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("確定",null).show();}
    private void render(){if(candidate==null||isFinishing())return;
        JSONObject run=null;try{if(!runId.isEmpty())run=store.run(runId);}catch(Exception ignored){}
        String state=allowed()+"|"+ready()+"|"+ChipCaptureService.running()+"|"+(run==null?"":run.toString());if(state.equals(signature))return;signature=state;
        int y=scroll==null?0:scroll.getScrollY();scroll=new ScrollView(this);scroll.setBackgroundColor(0xff0a101b);LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(20),dp(30),dp(20),dp(30));scroll.addView(v);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,in)->{int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets x=in.getInsets(WindowInsets.Type.systemBars());top=x.top;bottom=x.bottom;}else{top=in.getSystemWindowInsetTop();bottom=in.getSystemWindowInsetBottom();}v.setPadding(dp(20),top+dp(18),dp(20),bottom+dp(24));return in;});
        text(v,"卡片截圖與網頁查詢",23,0xff65e6c4);text(v,candidate.optString("name")+"  "+candidate.optString("code")+(candidate.optBoolean("capture_test")?" · 手動截圖測試，非選股訊號":" · "+candidate.optInt("matched")+"/6"),20,0xffeef4fb);
        text(v,"K線 → 籌碼日報 → 法人 → 多空 → 主力 → 大戶\n六頁完成後保存原圖與ZIP，並在Chrome的Google搜尋AI模式附加六張圖片及分析問題，再嘗試將可見回覆帶回APP。不使用Gemini API、不需API金鑰。首次需完成Google AI模式登入；Google可能要求手動選圖或驗證。",14,0xffeef4fb);
        Switch consent=new Switch(this);consent.setText("允許本次功能自動收集並上傳六張截圖至Google搜尋AI模式");consent.setTextColor(0xffeef4fb);consent.setChecked(allowed());consent.setOnCheckedChangeListener((b,on)->{getSharedPreferences("portfolio_settings",0).edit().putBoolean(CONSENT,on).apply();if(!on&&runId.equals(ChipCaptureService.currentRunId()))ChipCaptureService.cancel();signature="";handler.post(()->{maybeStart();render();});});v.addView(consent);
        if(run==null){
            text(v,"首次設定（之後不需逐張確認）",18,0xfff5c870);
            text(v,(Build.VERSION.SDK_INT>=34?"✓ Android版本支援":"✗ 需要Android 14以上")+"\n"+(installed()?"✓ 已安裝籌碼K線":"✗ 請先安裝並登入籌碼K線")+"\n"+(ChipCaptureService.available()?"✓ 輔助使用已開啟":"✗ 尚未開啟漲停雷達的輔助使用服務")+"\n"+(getPackageManager().getLaunchIntentForPackage(GoogleSearchCapture.CHROME)!=null?"✓ 已安裝Google Chrome":"✗ 請先安裝Google Chrome並完成首次啟動"),14,0xffeef4fb);
            button(v,"開啟Chrome完成首次設定",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/")).setPackage(GoogleSearchCapture.CHROME));}catch(Exception e){show("請安裝Chrome","請先安裝Google Chrome。");}});
            button(v,"開啟輔助使用設定",()->{try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(Exception e){show("請開啟系統設定","在手機設定 → 輔助使用 → 漲停雷達，手動啟用服務。");}});
            button(v,"啟用並開始",()->{getSharedPreferences("portfolio_settings",0).edit().putBoolean(CONSENT,true).apply();if(!ready())show("請先完成首次設定","需要Android 14以上、籌碼K線、Chrome與輔助使用權限。全部完成後會自動開始本張卡片的收集。");maybeStart();signature="";render();});
            if(ChipCaptureService.running())text(v,"已有其他收集任務，請先完成或停止。",14,0xfff5c870);
        }else{
            text(v,"本次進度 "+run.optJSONObject("pages").length()+"/6\n"+run.optString("status"),16,0xfff5c870);
            if(runId.equals(ChipCaptureService.currentRunId())&&ChipCaptureService.running())button(v,"停止本次收集",ChipCaptureService::cancel);
            String id=runId;button(v,"查看六頁圖片與歷史紀錄",()->startActivity(new Intent(this,PortfolioActivity.class).putExtra(CandidateCapture.RUN,id)));
            if(run.has("bundle"))button(v,"匯出本次六頁ZIP",()->{try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,candidate.optString("code")+"-six-pages.zip"),61);}catch(Exception e){show("無法匯出",PortfolioActivity.safe(e));}});
            if("google_ai_images".equals(run.optString("web_mode"))){button(v,"複製本次AI分析問題",()->{getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("本次AI分析問題",GoogleAiCapture.prompt(id,candidate.optString("code"),candidate.optString("name"))));show("已複製","可貼到Google搜尋AI模式，並選取本次六張圖片。");});}
            if(run.has("web_result")){JSONObject result=run.optJSONObject("web_result");text(v,result.optString("kind").contains("google_ai")?"Google AI模式分析回覆":"Google網頁查詢結果",19,0xff65e6c4);text(v,webText(result),14,0xffeef4fb);button(v,"開啟Google搜尋來源",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(result.optString("source_url"))).setPackage(GoogleSearchCapture.CHROME)));}
            if(!ChipCaptureService.running()){button(v,"再次開啟本次Chrome任務",()->{try{ChipCaptureService.retryWeb(id);}catch(Exception e){show("無法查詢",PortfolioActivity.safe(e));}});button(v,"貼回Google AI分析／搜尋結果",()->pasteResult(id));
                button(v,"重新收集此股六頁",()->{try{startActivity(CandidateCapture.intent(this,candidate));finish();}catch(Exception e){show("無法重新收集",PortfolioActivity.safe(e));}});}
        }
        text(v,"收集時保持籌碼K線在前景，不操作手機。存圖前以本機OCR核對股號與頁面；圖示與頁籤不可讀時使用畫面辨識備援。Chrome僅處理本次Google AI問題與可見回覆，選檔器只提供本次六張圖15分鐘。未核對附件或未送出時會顯示未完成，不會將一般搜尋當成圖片分析。舊的一般搜尋紀錄仍不傳圖片。若需要登入、訂閱或APP禁止截圖，請先在籌碼K線處理。",12,0xff8fa3ba);
        button(v,"返回雷達",this::finish);scroll.post(()->scroll.scrollTo(0,y));
    }
    public static String webText(JSONObject result){StringBuilder text=new StringBuilder(result.optString("note"));java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.TAIWAN);date.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Taipei"));text.append("\n查詢時間（台北）：").append(date.format(new java.util.Date(result.optLong("queried"))));text.append("\n\n查詢：").append(result.optString("query"));org.json.JSONArray rows=result.optJSONArray("snippets");if(rows!=null)for(int i=0;i<rows.length();i++)text.append("\n\n• ").append(rows.optString(i));return text.toString();}
    private void pasteResult(String id){
        EditText input=new EditText(this);input.setHint("貼上本次Google AI模式的回覆與來源連結");input.setMinLines(5);input.setMaxLines(10);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("貼回Google回覆").setView(input).setPositiveButton("保存",(d,w)->savePasted(id,input.getText().toString())).setNegativeButton("取消",null).create();dialog.show();
    }
    private void savePasted(String id,String value){try{value=value.trim();if(value.isEmpty()||value.length()>12000)throw new IllegalArgumentException("請輸入1至12000字摘要");JSONObject run=store.run(id);boolean ai="google_ai_images".equals(run.optString("web_mode"));run.put("web_result",new JSONObject().put("kind",ai?"manual_google_ai":"manual_search_excerpt").put("query",GoogleSearchCapture.query(candidate.optString("code"),candidate.optString("name"))).put("source_url",ai?"https://www.google.com/ai":GoogleSearchCapture.url(candidate.optString("code"),candidate.optString("name"))).put("queried",System.currentTimeMillis()).put("snippets",new JSONArray().put(value)).put("note","使用者手動貼回的Google內容，未自動核對附件與來源，請自行核對。")).put("status","已保存手動查詢摘要");store.updateRun(run);signature="";render();}catch(Exception e){show("未保存",PortfolioActivity.safe(e));}}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=61||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();String id=runId;worker.execute(()->{try{JSONObject r=store.run(id);File bundle=new File(store.imageFile(id,PortfolioStore.PAGES[0]).getParentFile(),"six-pages.zip");if(!r.has("bundle")||!bundle.isFile())throw new IOException("圖片已變更，請重新打包");
            try(InputStream in=new FileInputStream(bundle);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("無法寫入選取位置");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}handler.post(()->show("已匯出","六頁圖片與分類清單已保存。"));
        }catch(Exception e){handler.post(()->show("匯出失敗",PortfolioActivity.safe(e)));}});
    }
}
