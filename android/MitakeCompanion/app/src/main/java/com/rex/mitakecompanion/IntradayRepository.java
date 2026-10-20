package com.rex.mitakecompanion;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Shared bounded cache and two download workers; only visible cards request data. */
public final class IntradayRepository implements AutoCloseable {
    interface Callback {void result(IntradayData data,String error);}
    private static final long TTL=60000;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(12));
    private final Map<String,List<Callback>> pending=new HashMap<>();
    private final LinkedHashMap<String,Entry> cache=new LinkedHashMap<>(64,.75f,true);
    private boolean closed;
    private static final class Entry {final IntradayData data;final String error;final long at;Entry(IntradayData d,String e){data=d;error=e;at=SystemClock.elapsedRealtime();}}
    void load(String code,Callback callback){
        if(closed)return;
        if(!IntradayData.validCode(code)){callback.result(null,"股號格式待確認，暫不載入圖表");return;}
        Entry hit=cache.get(code);if(hit!=null&&SystemClock.elapsedRealtime()-hit.at<TTL){callback.result(hit.data,hit.error);return;}
        List<Callback> existing=pending.get(code);if(existing!=null){existing.add(callback);return;}
        List<Callback> list=new ArrayList<>();list.add(callback);pending.put(code,list);
        try{pool.execute(()->{
            IntradayData data=null;String error="";
            for(String suffix:new String[]{".TW",".TWO"}){
                try{data=IntradayData.parse(fetch("https://query1.finance.yahoo.com/v8/finance/chart/"+code+suffix+"?interval=5m&range=1d"),code+suffix);break;}
                catch(Exception e){error="五分K暫不可用（來源或網路無回應），點擊重試";}
            }
            IntradayData answer=data;String reason=data==null?error:"";
            main.post(()->{if(closed)return;cache.put(code,new Entry(answer,reason));while(cache.size()>60)cache.remove(cache.keySet().iterator().next());List<Callback> targets=pending.remove(code);if(targets!=null)for(Callback c:targets)c.result(answer,reason);});
        });}catch(RejectedExecutionException e){pending.remove(code);callback.result(null,"圖表載入排隊中，點擊重試");}
    }
    void retry(String code){cache.remove(code);}
    static String fetch(String address)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setRequestProperty("User-Agent","Mozilla/5.0 MitakeCompanion");
        try{if(c.getResponseCode()!=200)throw new IOException("來源未回傳資料");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("資料過大");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());}}finally{c.disconnect();}
    }
    @Override public void close(){closed=true;pool.shutdownNow();main.removeCallbacksAndMessages(null);pending.clear();cache.clear();}
}
