package com.rex.mitakecompanion.portfolio;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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

/** Deterministic, user-started collection. Only verified stock windows and bounded tab-strip gestures; no background scanning. */
public final class ChipCaptureService extends AccessibilityService {
    static final String PACKAGE="com.cmoney.chipk";
    private static volatile ChipCaptureService instance;
    private static volatile boolean active;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String runId="",code="",name="",lastUi="",requestedTab="";private int page,searchAttempts,token;
    private final Set<String> configured=new HashSet<>();
    private int tabScrolls;private boolean stockDetail,screenshotBusy;private long deadline,stableSince;private LinearLayout overlay;private TextView overlayText;
    private PortfolioStore store;private GoogleSearchCapture webTask;private GoogleAiCapture aiTask;
    private long lastOcr;private boolean clipboardPrepared;private boolean forceOcr;
    private ChipPositionMemory positions;private ChipScreenOcr screenOcr;private final ChipRecovery recovery=new ChipRecovery();
    private List<ChipScreenGuide.Line> verifiedLines=Collections.emptyList();private int verifiedWidth,verifiedHeight;
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
        s.positions=new ChipPositionMemory(s);s.configured.clear();s.requestedTab="";s.lastOcr=0;s.clipboardPrepared=false;s.aiTask=null;s.webTask=null;s.runId=id;s.code=code;s.name=r.getJSONObject("holding").getString("name");s.page=ChipRecovery.nextMissing(r);if(s.page>=6)throw new IllegalStateException("六頁已完成，請使用Chrome分析按鈕");s.forceOcr=s.page>0;s.recovery.reset();s.verifiedLines=Collections.emptyList();s.searchAttempts=0;s.tabScrolls=0;s.stockDetail=false;s.token++;s.screenshotBusy=false;s.lastUi="";s.stableSince=SystemClock.elapsedRealtime();s.deadline=SystemClock.elapsedRealtime()+90000;
        s.showOverlay();active=true;s.store.status(id,"等待確認個股 "+code);try{s.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));s.handler.postDelayed(s.tick,1500);}catch(RuntimeException e){s.finish("無法開啟籌碼K線，未開始收集");throw new IllegalStateException("無法開啟籌碼K線");}
    }
    @Override protected void onServiceConnected(){super.onServiceConnected();store=PortfolioStore.get(this);positions=new ChipPositionMemory(this);instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){finish("輔助使用已中斷，未完成收集");}
    @Override public void onDestroy(){finish("收集服務已停止");instance=null;worker.execute(()->{if(screenOcr!=null){screenOcr.close();screenOcr=null;}});worker.shutdown();super.onDestroy();}
    private void showOverlay(){
        overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.VERTICAL);overlay.setPadding(12,8,12,8);overlay.setBackgroundColor(0xf0223344);
        overlayText=new TextView(this);overlayText.setTextColor(Color.WHITE);overlayText.setText("準備收集 "+code);overlay.addView(overlayText);
        Button stop=new Button(this);stop.setText("停止收集");stop.setOnClickListener(v->finish("使用者已停止收集"));overlay.addView(stop);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);p.gravity=Gravity.END|Gravity.BOTTOM;p.y=96;
        try{((WindowManager)getSystemService(WINDOW_SERVICE)).addView(overlay,p);}catch(RuntimeException e){overlay=null;throw new IllegalStateException("無法顯示停止控制，未啟動收集");}
    }
    private void finish(String status){
        boolean wasActive=active;active=false;webTask=null;aiTask=null;token++;screenshotBusy=false;handler.removeCallbacks(tick);
        if(overlay!=null){try{((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(overlay);}catch(RuntimeException ignored){}overlay=null;}
        if(wasActive)CaptureDocuments.close(this);
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
    static boolean pageLabel(String label,String page){return ChipVideoProfile.tabLabel(label,page);}
    // The recording has a second set of 主力/法人/大戶 controls at the bottom.
    // Only the horizontal navigation strip below the quote header is a page tab.
    static boolean inTabStrip(Rect bounds,Rect screen){return bounds.width()>0&&bounds.height()>0&&bounds.centerY()>screen.top+screen.height()*.18&&bounds.centerY()<screen.top+screen.height()*.40&&bounds.height()<screen.height()*.09;}
    private AccessibilityNodeInfo tab(List<AccessibilityNodeInfo> list,String target){
        Rect screen=new Rect();if(list.isEmpty())return null;list.get(0).getBoundsInScreen(screen);
        AccessibilityNodeInfo found=null;
        for(AccessibilityNodeInfo n:list){if(!pageLabel(label(n),target))continue;Rect b=new Rect();n.getBoundsInScreen(b);if(!inTabStrip(b,screen))continue;
            if(found!=null)return null;found=n;
        }return found;
    }
    private int navigationBottom(AccessibilityNodeInfo root){
        Rect screen=new Rect();root.getBoundsInScreen(screen);int bottom=screen.top+(int)(screen.height()*.18);
        for(AccessibilityNodeInfo n:nodes(root)){Rect b=new Rect();n.getBoundsInScreen(b);if(ChipVideoProfile.order(label(n))>=0&&inTabStrip(b,screen))bottom=Math.max(bottom,b.bottom);}return bottom;
    }
    private String bodyText(AccessibilityNodeInfo root){
        Rect screen=new Rect();root.getBoundsInScreen(screen);int bottom=navigationBottom(root);
        for(AccessibilityNodeInfo n:nodes(root)){Rect b=new Rect();n.getBoundsInScreen(b);if(ChipVideoProfile.order(label(n))>=0&&inTabStrip(b,screen))bottom=Math.max(bottom,b.bottom);}
        StringBuilder out=new StringBuilder();for(AccessibilityNodeInfo n:nodes(root)){Rect b=new Rect();n.getBoundsInScreen(b);if(b.top>=bottom&&b.height()<screen.height()*.5)out.append(label(n)).append('\n');}return out.toString();
    }
    private boolean pageReady(AccessibilityNodeInfo root,String target){
        AccessibilityNodeInfo t=tab(nodes(root),target);return t!=null&&(selected(t)||ChipVideoProfile.bodyMatches(bodyText(root),target));
    }
    private boolean tap(AccessibilityNodeInfo node){
        AccessibilityNodeInfo c=clickable(node);if(c!=null&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        Rect b=new Rect();node.getBoundsInScreen(b);if(b.isEmpty())return false;
        Path path=new Path();path.moveTo(b.centerX(),b.centerY());return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,70)).build(),null,null);
    }
    private boolean revealTab(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> list,String target){
        Rect screen=new Rect();root.getBoundsInScreen(screen);Rect strip=new Rect();List<String> visible=new ArrayList<>();
        for(AccessibilityNodeInfo n:list){Rect b=new Rect();n.getBoundsInScreen(b);if(ChipVideoProfile.order(label(n))>=0&&inTabStrip(b,screen)){strip.union(b);visible.add(label(n));}}
        if(visible.size()<2||strip.isEmpty())return false;
        boolean forward=ChipVideoProfile.scrollForward(target,visible);
        // Scroll only an actual tab container, never the chart or the broker list.
        for(AccessibilityNodeInfo n:list)if(n.isScrollable()&&n.isEnabled()){
            Rect b=new Rect();n.getBoundsInScreen(b);if(b.height()<screen.height()*.12&&b.contains(strip.centerX(),strip.centerY())&&n.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;
        }
        float left=Math.max(screen.left+screen.width()*.07f,strip.left+8),right=Math.min(screen.left+screen.width()*.85f,strip.right-8);
        if(right-left<screen.width()*.20)return false;
        Path path=new Path();path.moveTo(forward?right:left,strip.centerY());path.lineTo(forward?left:right,strip.centerY());
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,380)).build(),null,null);
    }
    private boolean configure(AccessibilityNodeInfo root,String target){
        for(String option:ChipVideoProfile.options(target)){
            String key=target+"|"+option;if(configured.contains(key))continue;
            Rect screen=new Rect();root.getBoundsInScreen(screen);AccessibilityNodeInfo found=null;
            for(AccessibilityNodeInfo n:nodes(root)){Rect b=new Rect();n.getBoundsInScreen(b);if(pageLabel(label(n),option)&&b.top>=navigationBottom(root)&&b.bottom<screen.top+screen.height()*.55&&b.height()<screen.height()*.07){if(found==null){found=n;}else{Rect previous=new Rect();found.getBoundsInScreen(previous);if(b.top<previous.top)found=n;}}}
            if(found==null)return false;
            if(selected(found)){configured.add(key);continue;}
            if(tap(found)){configured.add(key);stableSince=SystemClock.elapsedRealtime();}return false;
        }return true;
    }
    private static boolean headerCode(AccessibilityNodeInfo root,String code){
        Rect screen=new Rect();root.getBoundsInScreen(screen);
        for(AccessibilityNodeInfo n:nodes(root)){Rect b=new Rect();n.getBoundsInScreen(b);if(b.top<screen.top+screen.height()*.30&&exactCode(label(n),code))return true;}return false;
    }
    public static void retryWeb(String id)throws Exception{
        ChipCaptureService s=instance;if(s==null)throw new IllegalStateException("請先開啟輔助使用服務");if(active)throw new IllegalStateException("已有收集或查詢任務");
        JSONObject r=s.store.run(id);if(r.getJSONObject("pages").length()!=6)throw new IllegalStateException("請先完成六頁收集或匯入");
        s.aiTask=null;s.webTask=null;s.runId=id;s.code=r.getJSONObject("holding").getString("code");s.name=r.getJSONObject("holding").optString("name");s.token++;s.showOverlay();active=true;
        s.page=5;s.store.status(id,"六頁打包中，接著開啟Chrome查詢");s.packAndStartWeb();
    }
    private void packAndStartWeb(){
        final int generation=token;final String id=runId;screenshotBusy=true;
        worker.execute(()->{Exception packingFailure=null;try{AnalysisBundle.create(store,id);}catch(Exception e){packingFailure=e;}final Exception problem=packingFailure;
            handler.post(()->{if(!active||generation!=token)return;if(problem!=null){finish("六頁打包失敗，未啟動Google查詢");return;}try{startWeb();}catch(Exception e){finish("無法開啟Chrome，六頁已保留，請返回APP重新查詢");}});
        });
    }
    private void startWeb()throws Exception{
        if("google_ai_images".equals(store.run(runId).optString("web_mode"))){
            aiTask=new GoogleAiCapture(this,runId,code,name);webTask=null;deadline=SystemClock.elapsedRealtime()+300000;screenshotBusy=false;
            store.status(runId,"六頁已完成，準備透過Chrome附加圖片到Google AI模式");overlayText.setText(code+" · Google AI附圖中");overlayText.setVisibility(View.VISIBLE);overlayText.setSingleLine(true);overlayText.setTextSize(11);overlayText.setMaxWidth((int)(getResources().getDisplayMetrics().density*155));WindowManager.LayoutParams control=(WindowManager.LayoutParams)overlay.getLayoutParams();control.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;control.y=0;((WindowManager)getSystemService(WINDOW_SERVICE)).updateViewLayout(overlay,control);startActivity(aiTask.launch());handler.removeCallbacks(tick);handler.postDelayed(tick,1500);return;
        }
        webTask=new GoogleSearchCapture(this,runId,code,name);deadline=SystemClock.elapsedRealtime()+60000;screenshotBusy=false;
        store.status(runId,"六頁已打包，正在Chrome查詢公開資訊");overlayText.setText(code+" · Google搜尋中\n只帶回本次搜尋摘要，不上傳圖片");
        startActivity(webTask.launch());handler.removeCallbacks(tick);handler.postDelayed(tick,1500);
    }
    private void step()throws Exception{
        if(aiTask!=null){GoogleAiCapture task=aiTask;if(SystemClock.elapsedRealtime()>deadline){task.fail("Google AI模式未完成：請確認登入、六張附件與送出狀態；可在APP貼回結果，不自動重送。圖片保留。");finish(store.run(runId).optString("status"));return;}boolean done=task.step(getRootInActiveWindow());if(overlayText!=null)overlayText.setText(task.progress());if(done)finish(store.run(runId).optString("status"));return;}
        if(webTask!=null){GoogleSearchCapture task=webTask;
            if(SystemClock.elapsedRealtime()>deadline){task.fail("Google查詢逾時或無法核對搜尋頁，請返回APP重新查詢或貼上搜尋結果；六頁圖片仍保留。");String status=store.run(runId).optString("status");finish(status);return;}
            if(task.step(getRootInActiveWindow())){String status=store.run(runId).optString("status");finish(status);}return;
        }
        if(Build.VERSION.SDK_INT<34){finish("需要Android 14以上，請手動匯入圖片");return;}
        if(SystemClock.elapsedRealtime()>deadline){finish("收集暫停：未能確認"+code+"的「"+PortfolioStore.PAGES[Math.min(page,5)]+"」頁面；已保留進度，可返回APP從未完成頁繼續。未傳送Google。");return;}
        if(screenshotBusy)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||!PACKAGE.contentEquals(root.getPackageName())){overlayText.setText("等待籌碼K線 · "+code+"\n切換其他App時不讀取、不截圖");return;}
        if(forceOcr||page>0){recoverWithOcr(root);return;}
        List<AccessibilityNodeInfo> list=nodes(root);String ui=allText(root);
        int tabs=0;for(String p:PortfolioStore.PAGES)if(tab(list,p)!=null)tabs++;
        // Require the code in the top part of an actual six-tab stock detail page.
        Rect screen=new Rect();root.getBoundsInScreen(screen);boolean header=false;
        for(AccessibilityNodeInfo n:list){Rect b=new Rect();n.getBoundsInScreen(b);if(b.top<screen.top+screen.height()*.30&&exactCode(label(n),code))header=true;}
        if(!header){stockDetail=false;requestedTab="";search(list);recoverWithOcr(root);return;}
        if(tabs>=1)stockDetail=true;
        if(!stockDetail){search(list);recoverWithOcr(root);return;}
        String target=PortfolioStore.PAGES[page];AccessibilityNodeInfo tab=tab(list,target);
        overlayText.setText(code+" · "+(page+1)+"/6 "+target+"\n只存本機，尚未上傳");
        if(tab==null){
            if(tabScrolls<8&&SystemClock.elapsedRealtime()-stableSince>1200&&revealTab(root,list,target)){tabScrolls++;stableSince=SystemClock.elapsedRealtime();}else recoverWithOcr(root);return;
        }
        boolean ready=pageReady(root,target)||target.equals(requestedTab);
        String signature=code+"|"+target+"|"+ready;
        if(!signature.equals(lastUi)){lastUi=signature;stableSince=SystemClock.elapsedRealtime();}
        // Some CMoney tabs do not publish selected/checked. Verify the distinct body
        // instead; waiting for a selected flag alone previously stalled forever.
        if(!ready){if(SystemClock.elapsedRealtime()-stableSince>1500){if(tap(tab))requestedTab=target;stableSince=SystemClock.elapsedRealtime();}return;}
        if(!configure(root,target)){recoverWithOcr(root);return;}
        if(ui.contains("載入中")||ui.contains("加載中")||SystemClock.elapsedRealtime()-stableSince<2000)return;
        capture(root.getWindowId(),target);
    }
    private void search(List<AccessibilityNodeInfo> list){
        overlayText.setText("確認個股 "+code+"\n若未自動搜尋，請在籌碼K線手動選股");
        if(searchAttempts>=8||list.isEmpty())return;
        // Only fill a single text input explicitly labelled as a stock search; never generic fields.
        List<AccessibilityNodeInfo> editors=new ArrayList<>();
        for(AccessibilityNodeInfo n:list)if(n.isEditable()&&n.isEnabled())editors.add(n);
        if(editors.size()==1){AccessibilityNodeInfo e=editors.get(0);String hint=label(e)+" "+(e.getHintText()==null?"":e.getHintText());
            if(hint.contains("搜尋")||hint.contains("股號")||hint.contains("股票")||hint.contains("代號")||(hasLabel(list,"搜尋")&&hasLabel(list,"個股"))){
                if(!code.equals(e.getText()==null?"":e.getText().toString())){Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,code);if(e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)){searchAttempts++;stableSince=SystemClock.elapsedRealtime();return;}}
                if(SystemClock.elapsedRealtime()-stableSince<1200)return;
                int suggestions=Integer.MAX_VALUE;for(AccessibilityNodeInfo n:list)if(label(n).contains("大家都在搜")){Rect b=new Rect();n.getBoundsInScreen(b);suggestions=Math.min(suggestions,b.top);}
                Rect editBounds=new Rect();e.getBoundsInScreen(editBounds);AccessibilityNodeInfo result=null;
                for(AccessibilityNodeInfo n:list)if(!n.isEditable()&&ChipVideoProfile.stockResult(label(n),code)){
                    Rect candidateBounds=new Rect();n.getBoundsInScreen(candidateBounds);if(candidateBounds.top<editBounds.bottom||candidateBounds.bottom>=suggestions)continue;
                    AccessibilityNodeInfo row=clickable(n);if(row==null||allText(row).contains("大家都在搜"))continue;String text=allText(row);if(text.isEmpty())continue;
                    if(result!=null&&!result.equals(row))return;result=row;
                }
                if(result!=null){tap(result);searchAttempts++;stableSince=SystemClock.elapsedRealtime();}
            }return;
        }
        if(editors.isEmpty()){
            for(AccessibilityNodeInfo n:list)if(label(n).equals("搜尋")||label(n).equals("搜尋股票")){if(tap(n)){searchAttempts++;stableSince=SystemClock.elapsedRealtime();return;}}
            // In the supplied video the stock name/code itself opens search.
            Rect screen=new Rect();list.get(0).getBoundsInScreen(screen);
            for(AccessibilityNodeInfo n:list){Rect b=new Rect();n.getBoundsInScreen(b);if(ChipVideoProfile.stockTitle(label(n))&&b.centerY()<screen.top+screen.height()*.14&&b.height()<screen.height()*.07&&b.centerX()>screen.left+screen.width()*.20&&b.centerX()<screen.left+screen.width()*.76){if(tap(n)){searchAttempts++;stableSince=SystemClock.elapsedRealtime();return;}}}
        }
    }
    private boolean hasLabel(List<AccessibilityNodeInfo> list,String text){for(AccessibilityNodeInfo n:list)if(label(n).equals(text))return true;return false;}

    private void acceptPage(File temp,String recognized,int generation,int windowId,String target)throws Exception{
        if(!active||generation!=token)return;AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||!PACKAGE.contentEquals(root.getPackageName())||root.getWindowId()!=windowId){finish("畫面已切換，未納入本次截圖");return;}
        final String id=runId;
        Rect anchor=ChipScreenGuide.anchor(verifiedLines,verifiedHeight,target);
        File dest=store.imageFile(id,target);java.nio.file.Files.move(temp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        store.attach(id,target,dest,"輔助使用擷取；本機OCR核對股號與頁面",recognized);positions.confirm(target,anchor,verifiedWidth,verifiedHeight,recognized,code);page=ChipRecovery.nextMissing(store.run(id));forceOcr=true;recovery.reset();requestedTab="";tabScrolls=0;screenshotBusy=false;
        if(page>=PortfolioStore.PAGES.length){if(store.run(id).optBoolean("auto_web")){packAndStartWeb();return;}
            boolean automatic=store.run(id).optBoolean("auto_analyze");finish(automatic?"六頁完成，準備依本次授權送至Gemini":"六頁收集完成，請返回股倉檢查");
            if(automatic)worker.execute(()->{try{PortfolioAnalysis.analyze(ChipCaptureService.this,id,true);}catch(Exception ignored){}});return;}
        deadline=SystemClock.elapsedRealtime()+120000;stableSince=SystemClock.elapsedRealtime();lastOcr=0;store.status(id,"已收集 "+store.run(id).getJSONObject("pages").length()+"/6 頁，準備辨識「"+PortfolioStore.PAGES[page]+"」");
    }
    private Rect windowBounds(AccessibilityNodeInfo root){Rect b=new Rect();for(AccessibilityWindowInfo w:getWindows())if(w.getId()==root.getWindowId()){w.getBoundsInScreen(b);break;}if(b.isEmpty())root.getBoundsInScreen(b);return b;}
    @TargetApi(34) void inspectGoogleComposer(GoogleAiCapture task,AccessibilityNodeInfo root,boolean send){
        if(!active||aiTask!=task||screenshotBusy||!task.visualAllowed(root,send))return;
        final int generation=token,windowId=root.getWindowId();final Rect viewport=windowBounds(root);screenshotBusy=true;
        takeScreenshotOfWindow(windowId,getMainExecutor(),new TakeScreenshotCallback(){
            public void onFailure(int error){if(generation==token){screenshotBusy=false;try{task.fail("Google按鈕畫面讀取失敗（"+error+"），六張圖片已保留，可回APP重新上傳。");}catch(Exception ignored){}}}
            public void onSuccess(ScreenshotResult result){HardwareBuffer buffer=result.getHardwareBuffer();Bitmap image=null;
                try{if(!active||generation!=token||aiTask!=task)return;Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware!=null){image=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();}}catch(RuntimeException e){try{task.fail("Google畫面暫時無法讀取，六張已保留，可重新上傳。");}catch(Exception ignored){}}finally{buffer.close();}
                if(image==null){if(generation==token)screenshotBusy=false;return;}final Bitmap capture=image;
                worker.execute(()->{int[] icons=null;int width=capture.getWidth(),height=capture.getHeight();Bitmap scaled=null;
                    try{if(width>900){scaled=Bitmap.createScaledBitmap(capture,900,Math.round((float)height*900/width),true);width=scaled.getWidth();height=scaled.getHeight();}Bitmap source=scaled==null?capture:scaled;int[] pixels=new int[width*height];source.getPixels(pixels,0,width,0,0,width,height);icons=GoogleComposerVision.find(pixels,width,height);}
                    catch(RuntimeException ignored){icons=null;}finally{if(scaled!=null)scaled.recycle();capture.recycle();}
                    final int[] found=icons;final int w=width,h=height;handler.post(()->{if(!active||generation!=token||aiTask!=task)return;screenshotBusy=false;AccessibilityNodeInfo current=getRootInActiveWindow();if(current==null||current.getWindowId()!=windowId||!task.visualAllowed(current,send))return;try{task.visualResult(found,viewport,w,h,send);}catch(Exception e){finish("Google附件操作失敗，六張圖片已保留");}});
                });
            }
        });
    }
    private boolean gesture(Rect b,Rect viewport,int width,int height,boolean swipe,boolean forward,long duration){
        float x1=viewport.left+(float)b.left/width*viewport.width(),x2=viewport.left+(float)b.right/width*viewport.width();float y=viewport.top+(float)b.centerY()/height*viewport.height();
        Path path=new Path();if(swipe){path.moveTo(forward?x2:x1,y);path.lineTo(forward?x1:x2,y);}else path.moveTo((x1+x2)/2,y);
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build(),null,null);
    }
    @TargetApi(34) private void recoverWithOcr(AccessibilityNodeInfo root){
        long now=SystemClock.elapsedRealtime();if(screenshotBusy||now-lastOcr<1600||now-stableSince<1200)return;
        lastOcr=now;screenshotBusy=true;final int generation=token,windowId=root.getWindowId();final Rect viewport=windowBounds(root);final String target=PortfolioStore.PAGES[page],expected=code;
        takeScreenshotOfWindow(windowId,getMainExecutor(),new TakeScreenshotCallback(){
            public void onFailure(int error){if(generation==token){screenshotBusy=false;storeStatus("讀取畫面失敗（"+error+"）；請確認籌碼K線允許截圖");}}
            public void onSuccess(ScreenshotResult result){HardwareBuffer buffer=result.getHardwareBuffer();Bitmap bitmap=null;
                try{if(!active||generation!=token)return;Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware!=null){bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();}}
                catch(RuntimeException e){if(generation==token)storeStatus("畫面讀取失敗，正在重試");}
                finally{buffer.close();}if(bitmap==null){screenshotBusy=false;return;}final Bitmap image=bitmap;
                worker.execute(()->{File temp=new File(getCacheDir(),"guide-"+UUID.randomUUID()+".jpg");try{int width=image.getWidth(),height=image.getHeight();PortfolioImages.write(image,temp);
                    if(screenOcr==null)screenOcr=new ChipScreenOcr();ChipScreenOcr.Result scan=screenOcr.read(image,expected,target,positions,!target.equals(requestedTab));List<ChipScreenGuide.Line> lines=scan.lines;
                    ChipScreenGuide.Action action=ChipScreenGuide.plan(lines,width,height,expected,target);String recognized=scan.text();handler.post(()->{
                        try{if(!active||generation!=token)return;screenshotBusy=false;AccessibilityNodeInfo current=getRootInActiveWindow();if(current==null||!PACKAGE.contentEquals(current.getPackageName())||current.getWindowId()!=windowId)return;
                            screenshotBusy=false;if(action==null){recovery.uncertain();storeStatus("放大重辨識「"+target+"」 · "+recovery.summary());if(recovery.exhausted())finish("「"+target+"」放大辨識仍未確認，已保留進度。可匯入此頁畫面學習位置，或從未完成頁繼續。");return;}
                            if(action.kind.equals("capture")){verifiedLines=lines;verifiedWidth=width;verifiedHeight=height;acceptPage(temp,recognized,generation,windowId,target);return;}
                            String observed=ChipVideoProfile.detectedPage(recognized);
                            if(ChipScreenGuide.headerMatches(lines,height,expected)&&action.kind.equals("tab")&&recovery.shouldSwipe(observed,target)){
                                boolean forward=ChipVideoProfile.order(target)>ChipVideoProfile.order(observed);Rect swipe=new Rect((int)(width*.16),(int)(height*.50),(int)(width*.80),(int)(height*.53));
                                if(gesture(swipe,viewport,width,height,true,forward,420)){recovery.swiped();requestedTab="";stableSince=SystemClock.elapsedRealtime();storeStatus("頁籤點擊尚未換頁，改用"+(forward?"向左":"向右")+"滑動，再核對「"+target+"」");}return;
                            }
                            if(recovery.exhausted()){finish("「"+target+"」翻頁尚未確認，已保留進度，可匯入學習畫面後續傳");return;}
                            if(action.kind.equals("input")){
                                android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);clipboard.setPrimaryClip(ClipData.newPlainText("雷達目標股號",expected));
                                boolean filled=false;for(AccessibilityNodeInfo n:nodes(current))if(n.isEditable()&&n.isEnabled()){Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,expected);if(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){filled=true;break;}}
                                if(!filled){gesture(action.box,viewport,width,height,false,false,clipboardPrepared?650:70);clipboardPrepared=true;}
                                storeStatus("填入股號 "+expected+"（僅複製本次股號）");
                            }else{boolean moved=gesture(action.box,viewport,width,height,action.kind.equals("swipe"),action.forward,action.kind.equals("swipe")?380:70);if(moved&&action.kind.equals("tab")){requestedTab=target;recovery.tapped();}if(moved&&action.kind.equals("swipe"))recovery.swiped();storeStatus(action.kind.equals("search")?"點擊左上角放大鏡":action.kind.equals("result")?"選取搜尋結果 "+expected:"切換 "+target);}
                            stableSince=SystemClock.elapsedRealtime();
                        }catch(Exception e){finish("畫面操作失敗，已保留截圖進度");}finally{temp.delete();}
                    });
                }catch(Exception e){handler.post(()->{if(generation==token){screenshotBusy=false;recovery.uncertain();storeStatus("分區放大辨識未完成，將重新定位 · "+recovery.summary());if(recovery.exhausted())finish("放大辨識多次未完成，已保留圖片與進度，可從未完成頁繼續");}});temp.delete();}finally{image.recycle();}});
            }
        });
    }
    private void storeStatus(String text){try{store.status(runId,text);if(overlayText!=null)overlayText.setText(code+" · "+(page+1)+"/6\n"+text);}catch(Exception ignored){}}
    @TargetApi(34) private void capture(int windowId,String target){
        final int generation=token;screenshotBusy=true;
        takeScreenshotOfWindow(windowId,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onFailure(int error){if(generation==token)finish("截圖失敗（"+error+"），畫面可能禁止擷取；請手動匯入。未傳送Google。");}
            @Override public void onSuccess(ScreenshotResult result){
                HardwareBuffer buffer=result.getHardwareBuffer();Bitmap software=null;
                try{if(!active||generation!=token)return;Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware==null)throw new IOException();software=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();}
                catch(Exception e){finish("截圖讀取失敗，請手動匯入");}finally{buffer.close();}
                if(software==null)return;final Bitmap bitmap=software;final String id=runId,expected=code;
                worker.execute(()->{File temp=new File(getCacheDir(),"capture-"+UUID.randomUUID()+".jpg");Exception failure=null;String ocr="";List<ChipScreenGuide.Line> observedLines=Collections.emptyList();int observedWidth=bitmap.getWidth(),observedHeight=bitmap.getHeight();
                    try{PortfolioImages.write(bitmap,temp);if(screenOcr==null)screenOcr=new ChipScreenOcr();ChipScreenOcr.Result scan=screenOcr.read(bitmap,expected,target,positions,false);ocr=scan.text();
                        if(!ChipScreenGuide.headerMatches(scan.lines,bitmap.getHeight(),expected))throw new IOException("截圖股號未通過文字核對");
                        if(!ChipVideoProfile.bodyMatches(ocr,target))throw new IOException("截圖頁面未通過文字核對");
                        observedLines=scan.lines;
                    }catch(Exception e){failure=e;}finally{bitmap.recycle();}
                    Exception error=failure;String recognized=ocr;final List<ChipScreenGuide.Line> imageLines=observedLines;final int imageWidth=observedWidth,imageHeight=observedHeight;
                    handler.post(()->{
                        try{
                            if(!active||generation!=token)return;
                            if(error!=null){screenshotBusy=false;forceOcr=true;requestedTab="";recovery.uncertain();stableSince=SystemClock.elapsedRealtime();storeStatus("「"+target+"」未通過核對，改用分區放大重新辨識");return;}
                            // The saved bitmap already passed stock AND page OCR; recheck the foreground stock after OCR.
                            AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null||!PACKAGE.contentEquals(root.getPackageName())){finish("畫面已切換，未納入本次截圖");return;}
                            if(root.getWindowId()!=windowId){finish("個股或頁面已切換，未納入本次截圖");return;}
                            verifiedLines=imageLines;verifiedWidth=imageWidth;verifiedHeight=imageHeight;acceptPage(temp,recognized,generation,windowId,target);

                        }catch(Exception e){finish("圖片儲存失敗，未完成收集");}finally{temp.delete();}
                    });
                });
            }
        });
    }
}
