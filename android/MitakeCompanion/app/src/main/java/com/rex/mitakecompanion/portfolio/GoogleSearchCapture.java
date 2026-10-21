package com.rex.mitakecompanion.portfolio;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.net.URI;
import java.util.*;

/** Bounded visible Google SERP excerpts. No browser cookies, private tabs or image upload. */
public final class GoogleSearchCapture {
    public static final String CHROME="com.android.chrome";
    private final Context context;private final PortfolioStore store;private final String id,query,url;
    private final LinkedHashSet<String> snippets=new LinkedHashSet<>();
    private String last="";private long changed;private final long opened=SystemClock.elapsedRealtime();private int scrolls;private boolean done;
    GoogleSearchCapture(Context context,String id,String code,String name){this.context=context;store=PortfolioStore.get(context);this.id=id;query=query(code,name);url=url(code,name);changed=SystemClock.elapsedRealtime();}
    public static String query(String code,String name){if(!PortfolioStore.code(code))throw new IllegalArgumentException("股號無效");String clean=name==null?"":name.replaceAll("[\\r\\n\\t]"," ").trim();if(clean.length()>40)clean=clean.substring(0,40);return code+" "+clean+" K線 籌碼日報 法人 多空 主力 大戶";}
    public static String url(String code,String name){return "https://www.google.com/search?hl=zh-TW&q="+Uri.encode(query(code,name));}
    public Intent launch(){return new Intent(Intent.ACTION_VIEW,Uri.parse(url)).setPackage(CHROME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    static boolean googleAddress(String address){try{String s=address.trim();URI u=URI.create(s.contains("://")?s:"https://"+s);String host=u.getHost();return "https".equals(u.getScheme())&&("google.com".equals(host)||"www.google.com".equals(host)||"google.com.tw".equals(host)||"www.google.com.tw".equals(host))&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443);}catch(Exception e){return false;}}
    static boolean searchPage(String address,Collection<String> labels){
        if(!googleAddress(address))return false;
        try{URI u=URI.create(address.contains("://")?address:"https://"+address);if("/search".equals(u.getPath()))return true;}catch(Exception ignored){}
        for(String s:labels)if(s.equals("搜尋結果")||s.equals("Search results")||s.equals("Search Results"))return true;return false;
    }
    static boolean sameQuery(String a,String b){return a.replaceAll("\\s+"," ").trim().equals(b.replaceAll("\\s+"," ").trim());}
    static List<String> excerpts(Collection<String> labels,String query){
        LinkedHashSet<String> clean=new LinkedHashSet<>();int total=0;
        for(String raw:labels){String s=raw.replaceAll("\\s+"," ").trim();if(s.length()<24||sameQuery(s,query)||s.startsWith("https://www.google.com/search")||s.contains("Cookie")||s.contains("隱私權政策"))continue;
            if(s.length()>1600)s=s.substring(0,1600);if(total+s.length()>12000)break;if(clean.add(s))total+=s.length();if(clean.size()>=12)break;
        }return new ArrayList<>(clean);
    }
    private static void walk(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out,int depth){if(n==null||depth>30||out.size()>1500)return;if(n.isVisibleToUser())out.add(n);for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),out,depth+1);}
    private static String label(AccessibilityNodeInfo n){String t=n.getText()==null?"":n.getText().toString();return t.isEmpty()&&n.getContentDescription()!=null?n.getContentDescription().toString():t;}
    /** Returns true only after a result/failure has been persisted. */
    boolean step(AccessibilityNodeInfo root)throws Exception{
        if(done)return true;if(root==null||!CHROME.contentEquals(root.getPackageName()))return false;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();walk(root,nodes,0);String address="";
        for(AccessibilityNodeInfo n:nodes){String view=n.getViewIdResourceName();if(view!=null&&view.equals(CHROME+":id/url_bar"))address=label(n);}
        if(!googleAddress(address))return snippets.isEmpty()?false:save();
        // A trusted hostname alone is insufficient: require the exact visible stock query.
        boolean expected=false;for(AccessibilityNodeInfo n:nodes)if(sameQuery(label(n),query))expected=true;
        if(!expected)return snippets.isEmpty()?false:save();
        List<String> labels=new ArrayList<>();for(AccessibilityNodeInfo n:nodes){String s=label(n);if(!s.isEmpty())labels.add(s);}
        if(!searchPage(address,labels))return snippets.isEmpty()?false:save();
        String visible=String.join("\n",labels);
        if(visible.contains("不是機器人")||visible.contains("異常流量")||visible.contains("unusual traffic")||visible.contains("驗證您是人類")){fail("Google要求驗證，請在Chrome手動處理後重新查詢；未擷取結果。");return true;}
        if(visible.contains("沒有符合")||visible.contains("找不到任何結果")){fail("Google沒有找到符合的公開搜尋結果。");return true;}
        long now=SystemClock.elapsedRealtime();if(!visible.equals(last)){last=visible;changed=now;if(now-opened<7000)return false;}if(now-changed<2500&&now-opened<7000)return false;
        snippets.addAll(excerpts(labels,query));
        android.graphics.Rect viewport=new android.graphics.Rect();root.getBoundsInScreen(viewport);
        if(scrolls<2)for(AccessibilityNodeInfo n:nodes)if(n.isScrollable()&&n.isEnabled()&&pageSized(n,viewport)&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)){scrolls++;changed=now;last="";return false;}
        if(snippets.isEmpty())return false;
        return save();
    }
    private boolean save()throws Exception{
        JSONObject result=new JSONObject().put("query",query).put("source_url",url).put("queried",System.currentTimeMillis()).put("kind","google_search_excerpt").put("snippets",new JSONArray(excerpts(snippets,query))).put("note","Chrome中可讀的公開搜尋摘要；非六張截圖的AI分析，須開啟來源核對日期與內容。");
        JSONObject run=store.run(id);run.put("web_result",result).put("status","Google網頁查詢完成，已帶回可見摘要");store.updateRun(run);done=true;return true;
    }
    private static boolean pageSized(AccessibilityNodeInfo node,android.graphics.Rect viewport){android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);return bounds.height()>viewport.height()*.45;}
    void fail(String reason)throws Exception{store.status(id,reason);done=true;}
    void returnToApp(){
        Intent view=new Intent(context,CandidateCaptureActivity.class).putExtra(CandidateCapture.RUN,id).setData(Uri.parse("mitake://web-result/"+id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try{if(Build.VERSION.SDK_INT<33||context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED){NotificationManager manager=context.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("web_results","網頁查詢結果",NotificationManager.IMPORTANCE_DEFAULT));manager.notify("web-"+id,5,new Notification.Builder(context,"web_results").setSmallIcon(com.rex.mitakecompanion.R.drawable.ic_notification).setContentTitle("Google網頁查詢 · 返回查看").setContentText("截圖與查詢紀錄已保存在APP").setContentIntent(PendingIntent.getActivity(context,0,view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build());}}catch(Exception ignored){}
        try{context.startActivity(view);}catch(RuntimeException ignored){}
    }
}
