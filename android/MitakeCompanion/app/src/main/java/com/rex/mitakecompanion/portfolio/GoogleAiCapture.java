package com.rex.mitakecompanion.portfolio;

import android.content.*;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.net.URI;
import java.util.*;
import org.json.*;

/** User-authorized Chrome AI Mode interaction; no API key, browser cookies or HTTP scraping. */
final class GoogleAiCapture {
    private final Context context;private final PortfolioStore store;private final String id,code,prompt,marker;
    private int stage,scrolls;private boolean sent,uploadVerified,pickerPending,ourPicker;private long changed,sentAt,lastAction;private String last="";
    private final Set<String> selected=new HashSet<>();private final LinkedHashSet<String> answer=new LinkedHashSet<>();
    GoogleAiCapture(Context context,String id,String code,String name)throws Exception{this.context=context;this.store=PortfolioStore.get(context);this.id=id;this.code=code;marker="雷達圖組"+id;prompt=prompt(id,code,name);CaptureDocuments.offer(context,id);JSONObject r=store.run(id);if(r.optBoolean("ai_submit_attempted")){sent=true;uploadVerified=r.optBoolean("ai_attachments_verified");sentAt=SystemClock.elapsedRealtime();stage=5;store.status(id,"本次已嘗試送出，請在Google AI歷史開啟本次圖組；不自動重送。");}}
    static String prompt(String id,String code,String name){if(!PortfolioStore.code(code))throw new IllegalArgumentException("股號無效");return "雷達圖組"+id+"。請用繁體中文分析 "+code+" "+(name==null?"":name.replaceAll("[\\r\\n]"," "))+" 的六張附圖，依序為K線、籌碼日報、法人、多空、主力、大戶。先核對每張股號、資料日期與分類。分別說明價量趨勢、法人與主力動向、大戶變化、支持與矛盾證據、主要風險與觀察失效條件；區分圖片事實、網頁來源及推論，附資料日期與來源。圖片不足或看不清楚請明說，不補造數字，不保證獲利。未提供股數與成本，請勿推估持股。";}
    Intent launch(){return new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/ai")).setPackage(GoogleSearchCapture.CHROME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    static boolean aiAddress(String address){if(!GoogleSearchCapture.googleAddress(address))return false;try{URI u=URI.create(address.contains("://")?address:"https://"+address);if("/ai".equals(u.getPath()))return true;return "/search".equals(u.getPath())&&u.getRawQuery()!=null&&Arrays.asList(u.getRawQuery().split("&")).contains("udm=50");}catch(Exception e){return false;}}
    private static String label(AccessibilityNodeInfo n){String t=n.getText()==null?"":n.getText().toString();return !t.isEmpty()?t:n.getContentDescription()==null?"":n.getContentDescription().toString();}
    private static void walk(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out,int depth){if(n==null||depth>35||out.size()>1800)return;if(n.isVisibleToUser())out.add(n);for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),out,depth+1);}
    private static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root){List<AccessibilityNodeInfo> out=new ArrayList<>();walk(root,out,0);return out;}
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n){for(int i=0;i<4&&n!=null;i++,n=n.getParent())if(n.isClickable()&&n.isEnabled())return n;return null;}
    private static AccessibilityNodeInfo exact(List<AccessibilityNodeInfo> nodes,String text){for(AccessibilityNodeInfo n:nodes)if(label(n).equals(text))return n;return null;}
    private static boolean click(List<AccessibilityNodeInfo> nodes,String... labels){for(String s:labels){AccessibilityNodeInfo n=exact(nodes,s);if(n!=null){AccessibilityNodeInfo c=clickable(n);if(c!=null&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}}return false;}
    static boolean sixAttachments(Collection<String> labels){for(int i=0;i<6;i++){String filename=CaptureDocuments.name(i);boolean found=false;for(String label:labels)if(label.contains(filename)){found=true;break;}if(!found)return false;}return true;}
    private void status(String message)throws Exception{store.status(id,message);}
    boolean step(AccessibilityNodeInfo root)throws Exception{
        if(root==null)return false;String pkg=String.valueOf(root.getPackageName());long now=SystemClock.elapsedRealtime();if(now-lastAction<1200)return false;
        if(pkg.equals("com.google.android.documentsui")||pkg.equals("com.android.documentsui"))return picker(root,now);
        if(pickerPending&&!GoogleSearchCapture.CHROME.equals(pkg)){
            if(Arrays.asList("android","com.android.intentresolver","com.google.android.providers.media.module","com.android.providers.media.module").contains(pkg)){
                if(click(nodes(root),"瀏覽","Browse","檔案","Files","選擇檔案","Choose files"))lastAction=now;
            }return false;
        }
        if(!GoogleSearchCapture.CHROME.equals(pkg))return false;
        List<AccessibilityNodeInfo> all=nodes(root);
        if(pickerPending&&(stage==2||stage==3)&&click(all,"檔案","Files","瀏覽","Browse")){lastAction=now;return false;}
        String address="";AccessibilityNodeInfo web=null;
        for(AccessibilityNodeInfo n:all){if((GoogleSearchCapture.CHROME+":id/url_bar").equals(n.getViewIdResourceName()))address=label(n);if(web==null&&n.getClassName()!=null&&"android.webkit.WebView".contentEquals(n.getClassName()))web=n;}
        if(!GoogleSearchCapture.googleAddress(address)||web==null)return false;
        List<AccessibilityNodeInfo> page=nodes(web);List<String> labels=new ArrayList<>();for(AccessibilityNodeInfo n:page)if(!label(n).isEmpty())labels.add(label(n));String text=String.join("\n",labels);
        if(!aiAddress(address)&&!labels.contains("AI 模式")&&!labels.contains("AI模式")&&!labels.contains("AI Mode"))return false;
        if(text.contains("異常流量")||text.contains("不是機器人")||text.contains("unusual traffic")){fail("Google要求驗證，請手動處理。六張圖片已保留，未把搜尋摘要當成分析。");return true;}
        if(!sent){
            List<AccessibilityNodeInfo> editors=new ArrayList<>();for(AccessibilityNodeInfo n:page)if(n.isEditable()&&n.isEnabled())editors.add(n);
            if(!text.contains(marker)){
                if(stage>0){status("等待本次AI問題出現；若網頁已送出，請保持回覆與問題可見");return false;}
                if(editors.size()!=1){status("請先完成Chrome的Google AI模式首次登入；六張圖已保留");return false;}
                Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,prompt);
                if(editors.get(0).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){stage=1;lastAction=now;status("AI問題已填入，準備附加本次六張圖片");}return false;
            }
            if(stage>=4&&attachmentsReady(labels,CaptureDocuments.chromeReads(context,id))){
                uploadVerified=true;JSONObject r=store.run(id);if(r.optBoolean("ai_submit_attempted")){fail("本次已嘗試送出，不自動重送；請在Chrome查看回覆或將結果貼回APP。");return true;}
                // Persist the one-shot receipt BEFORE clicking to prevent duplicate submissions.
                AccessibilityNodeInfo send=composerButton(page,editors,true);
                if(send!=null){r.put("ai_submit_attempted",true).put("ai_attachments_verified",true);store.updateRun(r);
                    sent=true;sentAt=now;changed=now;lastAction=now;if(!activate(send)){fail("六張附件已確認，但送出操作失敗；請在Chrome檢查，避免重複送出。");return true;}status("六張圖片已確認，已自動點擊送出，等待Google AI回覆");}else status("六張附件已確認，等待可用的送出箭頭");return false;
            }
            if(editors.isEmpty()&&responseControls(text)){fail("Google已出現回覆，但本次未確認六張附件，未當成圖片分析；請返回APP重新啟動圖片分析。");return true;}
            if(stage==1){if(activate(composerButton(page,editors,false))){stage=2;pickerPending=true;lastAction=now;status("請在選檔器選擇「"+CaptureDocuments.ROOT+"」中的六張圖片");}return false;}
            if(stage==2&&click(page,"上傳檔案","Upload files","選擇檔案","Choose files","上傳圖片","Upload image","從裝置上傳","Upload from device","上傳檔案或圖片","Upload files or images","相片和檔案","Photos and files","檔案","Files","從裝置選取","Select from device")){stage=3;pickerPending=true;lastAction=now;return false;}
            status("等待附加六張圖片；若未自動選取，請選「"+CaptureDocuments.ROOT+"」六張，再送出AI問題");return false;
        }
        // Read only a response bound to this run, not another chat or Google page.
        if(!text.contains(marker))return answer.isEmpty()?false:save(address);
        if(missingImages(text)){fail("Google表示沒有收到圖片，本次圖片分析失敗；六張截圖已保留，請重新啟動圖片分析。");return true;}
        if(!responseControls(text))return false;
        if(text.contains("正在思考")||text.contains("正在產生")||text.contains("正在搜尋")||text.contains("Thinking")||text.contains("Searching")){changed=now;return false;}
        List<String> got=answers(labels,marker,prompt);String signature=String.join("\n",got);
        if(signature.isEmpty())return false;if(!signature.equals(last)){last=signature;changed=now;return false;}
        if(now-sentAt<10000||now-changed<4500)return false;answer.addAll(got);
        android.graphics.Rect viewport=new android.graphics.Rect();root.getBoundsInScreen(viewport);
        if(scrolls<3)for(AccessibilityNodeInfo n:page)if(n.isScrollable()){android.graphics.Rect b=new android.graphics.Rect();n.getBoundsInScreen(b);if(b.height()>viewport.height()*.4&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)){scrolls++;changed=now;last="";lastAction=now;return false;}}
        return save(address);
    }
    private boolean activate(AccessibilityNodeInfo node){
        if(node==null)return false;if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        if(!(context instanceof android.accessibilityservice.AccessibilityService))return false;
        Rect b=new Rect();node.getBoundsInScreen(b);if(b.isEmpty())return false;
        android.graphics.Path path=new android.graphics.Path();path.moveTo(b.centerX(),b.centerY());
        return ((android.accessibilityservice.AccessibilityService)context).dispatchGesture(new android.accessibilityservice.GestureDescription.Builder().addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path,0,70)).build(),null,null);
    }
    static boolean actionLabel(String raw,boolean send){String s=raw.trim().toLowerCase(Locale.ROOT);
        String[] names=send?new String[]{"傳送","送出","提交","send","submit","send message","submit question","傳送訊息","送出問題","提交問題","向上箭頭","arrow_upward","向上","send prompt"}:new String[]{"+","＋","add_2","新增檔案和圖片","新增圖片和檔案","add files and images","新增圖片","add images","新增檔案","add files","新增附件","新增","add","上傳圖片","upload image","新增檔案或圖片","add files or images","附加檔案","attach files"};
        return Arrays.asList(names).contains(s);
    }
    // Icon-only controls must belong to the SAME composer as the unique run editor.
    // Pick the outer buttons on its bottom action row; never use screen-wide fixed pixels.
    static AccessibilityNodeInfo composerButton(List<AccessibilityNodeInfo> page,List<AccessibilityNodeInfo> editors,boolean send){
        if(editors.size()!=1)return null;AccessibilityNodeInfo editor=editors.get(0),parent=editor.getParent();
        Rect eb=new Rect();editor.getBoundsInScreen(eb);
        for(int level=0;level<4&&parent!=null;level++,parent=parent.getParent()){
            Rect box=new Rect();parent.getBoundsInScreen(box);
            if(box.width()<eb.width()||box.height()>eb.height()+Math.max(240,eb.width()/2))continue;
            List<AccessibilityNodeInfo> buttons=new ArrayList<>();
            for(AccessibilityNodeInfo n:nodes(parent)){
                Rect b=new Rect();n.getBoundsInScreen(b);AccessibilityNodeInfo c=clickable(n);
                if(c==null||!box.contains(b)||b.isEmpty()||b.width()>box.width()/3||b.height()>box.width()/3)continue;
                if(actionLabel(label(n),send))return c;
                String cl=String.valueOf(n.getClassName());
                if(n.isClickable()&&n.isEnabled()&&cl.endsWith("Button")&&label(n).isEmpty()&&b.centerY()>=eb.bottom-20)buttons.add(n);
            }
            if(buttons.size()<2)continue;
            List<Rect> boxes=new ArrayList<>();for(AccessibilityNodeInfo n:buttons){Rect b=new Rect();n.getBoundsInScreen(b);boxes.add(b);}
            int index=iconIndex(box,boxes,send);if(index>=0)return buttons.get(index);
        }return null;
    }
    static int iconIndex(Rect composer,List<Rect> boxes,boolean send){
        if(boxes.size()<2||composer.isEmpty())return -1;int row=0;for(Rect b:boxes)row=Math.max(row,b.centerY());
        int chosen=-1,edge=send?Integer.MIN_VALUE:Integer.MAX_VALUE;
        for(int i=0;i<boxes.size();i++){Rect b=boxes.get(i);if(!composer.contains(b)||Math.abs(b.centerY()-row)>b.height()/2)continue;
            if((send&&b.centerX()>edge)||(!send&&b.centerX()<edge)){edge=b.centerX();chosen=i;}}
        return chosen>=0&&((send&&edge>composer.left+composer.width()*.8)||(!send&&edge<composer.left+composer.width()*.2))?chosen:-1;
    }
    static boolean attachmentsReady(Collection<String> labels,int chromeReads){
        if(chromeReads!=6)return false;for(String s:labels){String t=s.toLowerCase(Locale.ROOT);if(t.contains("上傳中")||t.contains("uploading"))return false;}if(sixAttachments(labels))return true;
        int remove=0;for(String s:labels){String t=s.toLowerCase(Locale.ROOT);if(t.startsWith("移除圖片")||t.startsWith("移除附件")||t.startsWith("remove image")||t.startsWith("remove attachment")||t.startsWith("刪除圖片"))remove++;
            if(t.contains("上傳中")||t.contains("uploading"))return false;}
        return remove==6;
    }
    static boolean missingImages(String text){String s=text.toLowerCase(Locale.ROOT);return s.contains("並沒有收到或看到")||s.contains("沒有收到任何圖片")||s.contains("未收到圖片")||s.contains("沒有收到圖片")||s.contains("no images were uploaded")||s.contains("haven't received any images")||s.contains("no images attached");}
    private static boolean responseControls(String text){return text.contains("複製回覆")||text.contains("Copy response")||text.contains("回覆有幫助")||text.contains("Good response")||text.contains("Bad response");}
    static List<String> answers(Collection<String> labels,String marker,String prompt){LinkedHashSet<String> result=new LinkedHashSet<>();int total=0;for(String raw:labels){String s=raw.trim();if(s.length()<50||s.contains(marker)||s.equals(prompt)||s.contains("請用繁體中文分析")||s.contains("Google 帳戶")||s.contains("Google Account")||s.contains("Cookie")||s.contains("@"))continue;if(s.length()>6000)s=s.substring(0,6000);if(total+s.length()>24000)break;if(result.add(s))total+=s.length();}return new ArrayList<>(result);}
    private boolean picker(AccessibilityNodeInfo root,long now)throws Exception{
        if(!pickerPending)return false;List<AccessibilityNodeInfo> page=nodes(root);List<String> labels=new ArrayList<>();for(AccessibilityNodeInfo n:page)labels.add(label(n));
        if(!ourPicker){if(click(page,CaptureDocuments.ROOT)){ourPicker=true;lastAction=now;return false;}if(click(page,"顯示根目錄","Show roots","開啟導覽抽屜","Open navigation drawer")){lastAction=now;return false;}status("請在檔案選擇器點「"+CaptureDocuments.ROOT+"」，只提供本次六張圖");return false;}
        // Never select from Recent/Photos/another folder: all six exact names must be visible.
        if(!sixAttachments(labels)){status("等待顯示本次六張分類圖片；不選取其他相簿檔案");return false;}
        for(int i=0;i<6;i++){String name=CaptureDocuments.name(i);if(selected.contains(name))continue;AccessibilityNodeInfo n=exact(page,name),c=n==null?null:clickable(n);if(c==null)return false;
            boolean ok=selected.isEmpty()?c.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK):c.performAction(AccessibilityNodeInfo.ACTION_CLICK);if(ok){selected.add(name);lastAction=now;}return false;}
        if(click(page,"開啟","選取","Open","Select")){pickerPending=false;stage=4;lastAction=now;status("已在選檔器選取六張，等待Chrome讀取六張圖片及Google完成附圖");}return false;
    }
    private boolean save(String address)throws Exception{if(answer.isEmpty())return false;JSONObject result=new JSONObject().put("kind","google_ai_images").put("query",prompt).put("source_url",aiAddress(address)?(address.contains("://")?address:"https://"+address):"https://www.google.com/ai").put("queried",System.currentTimeMillis()).put("images_verified",uploadVerified).put("snippets",new JSONArray(answer)).put("note","Google搜尋AI模式的可見回覆；"+(uploadVerified?"送出前已確認Chrome讀取本次六張圖片，並核對附件名稱或六個移除圖片控制。":"未能核對六個附件檔名，請在Google核對圖片是否附上。")+"請核對股號、資料日期、來源與推論。並非經驗證的投資結論。");JSONObject run=store.run(id);run.put("web_result",result).put("status","已帶回Google AI模式可見回覆");store.updateRun(run);return true;}
    void fail(String message)throws Exception{store.status(id,message);}
}
