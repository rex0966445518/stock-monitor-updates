package com.rex.mitakecompanion.portfolio;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.content.*;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import com.google.mlkit.vision.text.Text;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Deterministic, user-started collection. No coordinate taps and no background scanning. */
public final class ChipCaptureService extends AccessibilityService {
    static final String PACKAGE="com.cmoney.chipk";
    private static volatile ChipCaptureService instance;
    private static volatile boolean active;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String runId="",code="",name="",lastUi="";private int page,searchAttempts,token;
    private int tabScrolls;private boolean stockDetail,screenshotBusy;private long deadline,stableSince;private LinearLayout overlay;private TextView overlayText;
    private PortfolioStore store;private GoogleSearchCapture webTask;
    public static boolean available(){return instance!=null;}
    public static boolean running(){return active;}
    public static String currentRunId(){ChipCaptureService s=instance;return s==null?"":s.runId;}
    public static void cancel(){ChipCaptureService s=instance;if(s!=null)s.handler.post(()->s.finish("已停止，保留已收集圖片"));}
    public static void begin(String id,String code)throws Exception{
        ChipCaptureService s=instance;if(s==null)throw new IllegalStateException("請先開啟輔助使用權限");
        if(Build.VERSION.SDK_INT<34)throw new IllegalStateException("自動收集需要Android 14以上");
        if(active)throw new IllegalStateException("已有收集任務，請先停止");
        if(!PortfolioStore.code(code))throw new IllegalArgumentException("股號無效");
        JSONObject r=s.store.run(id);if(!r.getJSONObject("holding").getString("code").equals(code))throw new IllegalArgumentException("目標股號不符");
        Intent launch=s.getPackageManager().getLaunchIntentForPackage(PACKAGE);if(launch==null)throw new IllegalStateException("請先安裝並登入籌碼K線");
        s.webTask=null;s.runId=id;s.code=code;s.name=r.getJSONObject("holding").getString("name");s.page=0;s.searchAttempts=0;s.tabScrolls=0;s.stockDetail=false;s.token++;s.screenshotBusy=false;s.lastUi="";s.stableSince=SystemClock.elapsedRealtime();s.deadline=SystemClock.elapsedRealtime()+45000;
        s.showOverlay();active=true;s.store.status(id,"等待確認個股 "+code);try{s.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));s.handler.postDelayed(s.tick,1500);}catch(RuntimeException e){s.finish("無法開啟籌碼K線，未開始收集");throw new IllegalStateException("無法開啟籌碼K線");}
    }
    @Override protected void onServiceConnected(){super.onServiceConnected();store=PortfolioStore.get(this);instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){finish("輔助使用已中斷，未完成收集");}
    @Override public void onDestroy(){finish("收集服務已停止");instance=null;worker.shutdown();super.onDestroy();}
    private void showOverlay(){
        overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.VERTICAL);overlay.setPadding(12,8,12,8);overlay.setBackgroundColor(0xf0223344);
        overlayText=new TextView(this);overlayText.setTextColor(Color.WHITE);overlayText.setText("準備收集 "+code);overlay.addView(overlayText);
        Button stop=new Button(this);stop.setText("停止收集");stop.setOnClickListener(v->finish("使用者已停止收集"));overlay.addView(stop);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);p.gravity=Gravity.END|Gravity.BOTTOM;p.y=96;
        try{((WindowManager)getSystemService(WINDOW_SERVICE)).addView(overlay,p);}catch(RuntimeException e){overlay=null;throw new IllegalStateException("無法顯示停止控制，未啟動收集");}
    }
    private void finish(String status){
        boolean wasActive=active;active=false;webTask=null;token++;screenshotBusy=false;handler.removeCallbacks(tick);
        if(overlay!=null){try{((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(overlay);}catch(RuntimeException ignored){}overlay=null;}
        if(wasActive&&!runId.isEmpty())try{store.status(runId,status);JSONObject run=store.run(runId);if(run.optBoolean("auto_web")&&!status.equals("收集服務已停止")&&!status.equals("輔助使用已中斷，未完成收集"))new GoogleSearchCapture(this,runId,code,name).returnToApp();}catch(Exception ignored){}
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        try{step();}catch(Exception e){finish("收集停止：畫面無法確認，請逐頁匯入截圖");}
        if(active)handler.postDelayed(this,900);
    }};
    static boolean exactCode(String text,String code){return Pattern.compile("(?<![0-9A-Za-z])"+Pattern.quote(code)+"(?![0-9A-Za-z])").matcher(text).find();}
    private static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root){List<AccessibilityNodeInfo> list=new ArrayList<>();walk(root,list,0);return list;}
    private static void walk(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out,int depth){if(node==null||depth>25||out.size()>1000)return;if(node.isVisibleToUser())out.add(node);for(int i=0;i<node.getChildCount();i++)walk(node.getChild(i),out,depth+1);}
    private static String label(AccessibilityNodeInfo n){String s=n.getText()==null?"":n.getText().toString();if(n.getContentDescription()!=null&&!n.getContentDescription().toString().equals(s))s+=" "+n.getContentDescription();return s.trim();}
    private static String allText(AccessibilityNodeInfo n){StringBuilder b=new StringBuilder();for(AccessibilityNodeInfo x:nodes(n))b.append(label(x)).append('\n');return b.toString();}
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n){for(int i=0;i<4&&n!=null;i++,n=n.getParent())if(n.isClickable()&&n.isEnabled())return n;return null;}
    private static boolean selected(AccessibilityNodeInfo n){for(int i=0;i<3&&n!=null;i++,n=n.getParent())if(n.isSelected()||n.isChecked())return true;return false;}
    static boolean pageLabel(String label,String page){return label.replaceAll("[\\s\u3000]+","").equals(page);}
    private AccessibilityNodeInfo tab(List<AccessibilityNodeInfo> nodes,String target){
        AccessibilityNodeInfo found=null;
        for(AccessibilityNodeInfo n:nodes){String t=label(n);if(!pageLabel(t,target))continue;AccessibilityNodeInfo click=clickable(n);if(click==null)continue;
            if(found!=null&&!clickable(found).equals(click))return null;found=n;}
        return found;
    }
    private static boolean headerCode(AccessibilityNodeInfo root,String code){
        Rect screen=new Rect();root.getBoundsInScreen(screen);
        for(AccessibilityNodeInfo n:nodes(root)){Rect b=new Rect();n.getBoundsInScreen(b);if(b.top<screen.top+screen.height()*.30&&exactCode(label(n),code))return true;}return false;
    }
    public static void retryWeb(String id)throws Exception{
        ChipCaptureService s=instance;if(s==null)throw new IllegalStateException("請先開啟輔助使用服務");if(active)throw new IllegalStateException("已有收集或查詢任務");
        JSONObject r=s.store.run(id);if(!r.has("bundle")||r.getJSONObject("pages").length()!=6)throw new IllegalStateException("請先完成六頁收集與打包");
        s.runId=id;s.code=r.getJSONObject("holding").getString("code");s.name=r.getJSONObject("holding").optString("name");s.token++;s.showOverlay();active=true;
        try{s.startWeb();}catch(Exception e){s.finish("無法啟動Chrome，請先完成Chrome首次設定後重試");throw e;}
    }
    private void startWeb()throws Exception{
        webTask=new GoogleSearchCapture(this,runId,code,name);deadline=SystemClock.elapsedRealtime()+60000;screenshotBusy=false;
        store.status(runId,"六頁已打包，正在Chrome查詢公開資訊");overlayText.setText(code+" · Google搜尋中\n只帶回本次搜尋摘要，不上傳圖片");
        startActivity(webTask.launch());handler.removeCallbacks(tick);handler.postDelayed(tick,1500);
    }
    private void step()throws Exception{
        if(webTask!=null){GoogleSearchCapture task=webTask;
            if(SystemClock.elapsedRealtime()>deadline){task.fail("Google查詢逾時或無法核對搜尋頁，請返回APP重新查詢或貼上搜尋結果；六頁圖片仍保留。");String status=store.run(runId).optString("status");finish(status);return;}
            if(task.step(getRootInActiveWindow())){String status=store.run(runId).optString("status");finish(status);}return;
        }
        if(Build.VERSION.SDK_INT<34){finish("需要Android 14以上，請手動匯入圖片");return;}
        if(SystemClock.elapsedRealtime()>deadline){finish("收集暫停：未能確認"+code+"的「"+PortfolioStore.PAGES[Math.min(page,5)]+"」頁面；請返回股倉手動匯入。未傳送Google。");return;}
        if(screenshotBusy)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||!PACKAGE.contentEquals(root.getPackageName())){overlayText.setText("等待籌碼K線 · "+code+"\n切換其他App時不讀取、不截圖");return;}
        List<AccessibilityNodeInfo> list=nodes(root);String ui=allText(root);
        int tabs=0;for(String p:PortfolioStore.PAGES)if(tab(list,p)!=null)tabs++;
        // Require the code in the top part of an actual six-tab stock detail page.
        Rect screen=new Rect();root.getBoundsInScreen(screen);boolean header=false;
        for(AccessibilityNodeInfo n:list){Rect b=new Rect();n.getBoundsInScreen(b);if(b.top<screen.top+screen.height()*.30&&exactCode(label(n),code))header=true;}
        if(!header){stockDetail=false;search(list);return;}
        if(tabs>=2)stockDetail=true;
        if(!stockDetail){search(list);return;}
        String target=PortfolioStore.PAGES[page];AccessibilityNodeInfo tab=tab(list,target);
        overlayText.setText(code+" · "+(page+1)+"/6 "+target+"\n只存本機，尚未上傳");
        if(tab==null){
            if(tabScrolls<4&&SystemClock.elapsedRealtime()-stableSince>1200)for(AccessibilityNodeInfo n:list)if(n.isScrollable()&&n.isEnabled()){
                Rect bounds=new Rect();n.getBoundsInScreen(bounds);int known=0;for(AccessibilityNodeInfo child:nodes(n))for(String p:PortfolioStore.PAGES)if(pageLabel(label(child),p))known++;
                if(known>0&&bounds.top<screen.top+screen.height()*.45&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)){tabScrolls++;stableSince=SystemClock.elapsedRealtime();return;}
            }return;
        }
        String signature=code+"|"+target+"|"+selected(tab);
        if(!signature.equals(lastUi)){lastUi=signature;stableSince=SystemClock.elapsedRealtime();}
        if(!selected(tab)){if(SystemClock.elapsedRealtime()-stableSince>1200){clickable(tab).performAction(AccessibilityNodeInfo.ACTION_CLICK);stableSince=SystemClock.elapsedRealtime();}return;}
        if(ui.contains("載入中")||ui.contains("加載中")||SystemClock.elapsedRealtime()-stableSince<1800)return;
        capture(root.getWindowId(),target);
    }
    private void search(List<AccessibilityNodeInfo> list){
        overlayText.setText("確認個股 "+code+"\n若未自動搜尋，請在籌碼K線手動選股");
        if(searchAttempts>=3)return;
        // Only fill a single text input explicitly labelled as a stock search; never generic fields.
        List<AccessibilityNodeInfo> editors=new ArrayList<>();
        for(AccessibilityNodeInfo n:list)if(n.isEditable()&&n.isEnabled())editors.add(n);
        if(editors.size()==1){AccessibilityNodeInfo e=editors.get(0);String hint=label(e)+" "+(e.getHintText()==null?"":e.getHintText());
            if(hint.contains("搜尋")||hint.contains("股號")||hint.contains("股票")){
                if(!code.equals(e.getText()==null?"":e.getText().toString())){Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,code);if(e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)){searchAttempts++;stableSince=SystemClock.elapsedRealtime();return;}}
                if(SystemClock.elapsedRealtime()-stableSince<1200)return;
                AccessibilityNodeInfo result=null;
                for(AccessibilityNodeInfo n:list)if(!n.isEditable()&&exactCode(label(n),code)){
                    AccessibilityNodeInfo row=clickable(n);if(row==null)continue;String text=allText(row);if(text.isEmpty())continue;
                    if(result!=null&&!result.equals(row))return;result=row;
                }
                if(result!=null){result.performAction(AccessibilityNodeInfo.ACTION_CLICK);searchAttempts++;stableSince=SystemClock.elapsedRealtime();}
            }return;
        }
        if(editors.isEmpty())for(AccessibilityNodeInfo n:list)if(label(n).equals("搜尋")||label(n).equals("搜尋股票")){AccessibilityNodeInfo c=clickable(n);if(c!=null){c.performAction(AccessibilityNodeInfo.ACTION_CLICK);searchAttempts++;stableSince=SystemClock.elapsedRealtime();return;}}
    }
    @TargetApi(34) private void capture(int windowId,String target){
        final int generation=token;screenshotBusy=true;
        takeScreenshotOfWindow(windowId,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onFailure(int error){if(generation==token)finish("截圖失敗（"+error+"），畫面可能禁止擷取；請手動匯入。未傳送Google。");}
            @Override public void onSuccess(ScreenshotResult result){
                HardwareBuffer buffer=result.getHardwareBuffer();Bitmap software=null;
                try{if(!active||generation!=token)return;Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware==null)throw new IOException();software=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();}
                catch(Exception e){finish("截圖讀取失敗，請手動匯入");}finally{buffer.close();}
                if(software==null)return;final Bitmap bitmap=software;final String id=runId,expected=code;
                worker.execute(()->{File temp=new File(getCacheDir(),"capture-"+UUID.randomUUID()+".jpg");Exception failure=null;String ocr="";
                    try{PortfolioImages.write(bitmap,temp);Text text=PortfolioImages.recognize(temp);ocr=text.getText();
                        boolean stock=false;for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines())if(line.getBoundingBox()!=null&&line.getBoundingBox().top<bitmap.getHeight()*.30&&exactCode(line.getText(),expected))stock=true;
                        if(!stock)throw new IOException("截圖股號未通過文字核對");
                    }catch(Exception e){failure=e;}finally{bitmap.recycle();}
                    Exception error=failure;String recognized=ocr;
                    handler.post(()->{
                        try{
                            if(!active||generation!=token)return;
                            if(error!=null){finish("截圖核對失敗，未納入圖片：請手動匯入「"+target+"」");return;}
                            // Recheck foreground and selected page after the asynchronous OCR.
                            AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null||!PACKAGE.contentEquals(root.getPackageName())){finish("畫面已切換，未納入本次截圖");return;}
                            AccessibilityNodeInfo tab=tab(nodes(root),target);if(tab==null||!selected(tab)||!headerCode(root,expected)){finish("個股或頁面已切換，未納入本次截圖");return;}
                            File dest=store.imageFile(id,target);java.nio.file.Files.move(temp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);store.attach(id,target,dest,"輔助使用擷取；股號已由本機OCR核對",recognized);
                            page++;tabScrolls=0;screenshotBusy=false;if(page>=PortfolioStore.PAGES.length){
                                if(store.run(id).optBoolean("auto_web")){
                                    screenshotBusy=true;worker.execute(()->{Exception error=null;try{AnalysisBundle.create(store,id);}catch(Exception e){error=e;}final Exception problem=error;handler.post(()->{if(!active||generation!=token)return;if(problem!=null){finish("六頁打包失敗，未啟動Google查詢");return;}try{startWeb();}catch(Exception e){finish("無法開啟Chrome，六頁已保留，請返回APP重新查詢");}});});return;
                                }
                                boolean automatic=store.run(id).optBoolean("auto_analyze");finish(automatic?"六頁完成，準備依本次授權送至Gemini":"六頁收集完成，請返回股倉檢查並送出分析");
                                if(automatic)worker.execute(()->{try{PortfolioAnalysis.analyze(ChipCaptureService.this,id,true);}catch(Exception ignored){}});return;
                            }
                            deadline=SystemClock.elapsedRealtime()+30000;stableSince=SystemClock.elapsedRealtime();store.status(id,"已收集 "+page+"/6 頁");
                        }catch(Exception e){finish("圖片儲存失敗，未完成收集");}finally{temp.delete();}
                    });
                });
            }
        });
    }
}
