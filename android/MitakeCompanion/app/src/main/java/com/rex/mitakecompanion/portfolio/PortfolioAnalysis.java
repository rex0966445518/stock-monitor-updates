package com.rex.mitakecompanion.portfolio;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Shared manual/opted-in automatic path; a run cannot send overlapping requests. */
public final class PortfolioAnalysis {
    private static final Set<String> IN_FLIGHT=ConcurrentHashMap.newKeySet();
    private PortfolioAnalysis(){}
    public static String analyze(Context context,String id,boolean requireSix)throws Exception{
        if(!IN_FLIGHT.add(id))throw new IllegalStateException("這筆分析正在執行，請稍候，未重複送出");
        PortfolioStore store=PortfolioStore.get(context);GeminiClient gemini=new GeminiClient(context);
        try{
            JSONObject r=store.run(id),pages=r.getJSONObject("pages");String revision=pages.toString();
            List<File> images=new ArrayList<>();List<String> labels=new ArrayList<>();StringBuilder missing=new StringBuilder();
            for(String page:PortfolioStore.PAGES){JSONObject image=pages.optJSONObject(page);if(image==null){missing.append(page).append("、");continue;}
                File f=new File(image.getString("path"));if(!f.getCanonicalFile().equals(store.imageFile(id,page).getCanonicalFile()))throw new IOException("圖片路徑無效");images.add(f);labels.add(page);
            }
            if(images.isEmpty()||requireSix&&images.size()!=6)throw new IllegalStateException("圖片不完整，未自動送出分析");
            if(!gemini.hasKey())throw new IllegalStateException("請先設定Gemini API金鑰");
            String model=gemini.model();String prompt=GeminiClient.ANALYZE+"\n使用者已核對的持股JSON："+r.getJSONObject("holding")+"\n缺圖："+missing+"\n圖片組建立時間（Unix毫秒，非行情日期）："+r.getLong("created");
            store.status(id,"Gemini分析中，請稍候");String answer=gemini.generate(prompt,images,labels,false);
            JSONObject latest=store.run(id);if(!revision.equals(latest.getJSONObject("pages").toString()))throw new IllegalStateException("分析期間圖片已變更，本次結果未儲存；請重新分析");
            latest.put("analysis",answer).put("analyzed",System.currentTimeMillis()).put("model",model).put("status","分析完成");store.updateRun(latest);return answer;
        }catch(Exception e){try{store.status(id,"分析未完成，可在股倉查看並重試");}catch(Exception ignored){}throw e;}finally{IN_FLIGHT.remove(id);}
    }
}
