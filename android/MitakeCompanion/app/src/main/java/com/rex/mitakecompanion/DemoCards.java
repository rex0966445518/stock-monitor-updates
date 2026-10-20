package com.rex.mitakecompanion;
import org.json.*;
/** UI-only examples. Never passed to the bridge, notification or persistence layers. */
public final class DemoCards {
    static JSONArray rows(){
        try{return new JSONArray().put(row("2330","台積電",5,2405,3.0)).put(row("2317","鴻海",6,200,4.2));}
        catch(JSONException e){throw new IllegalStateException(e);}
    }
    private static JSONObject row(String code,String name,int matched,double price,double gain)throws JSONException{
        return new JSONObject().put("demo",true).put("code",code).put("name",name).put("matched",matched).put("peak",matched).put("total",6)
            .put("price",price).put("gain",gain).put("volume",2000).put("confirmed",matched==6).put("valid",false).put("ts",0)
            .put("reason","這是卡片功能測試，條件、價格、漲跌幅與成交量皆為示範，並非真實行情。五分K另由公開來源取得，可能延遲。不觸發通知、不寫入掃描紀錄。")
            .put("source","UI-DEMO");
    }
}
