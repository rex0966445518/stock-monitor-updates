package com.rex.mitakecompanion;

import android.content.SharedPreferences;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Receipts survive reconnect/process restart. Snapshot rows fill missing events without looping alerts. */
final class SignalGate {
    private final SharedPreferences prefs;
    SignalGate(SharedPreferences prefs){this.prefs=prefs;}
    synchronized boolean accept(JSONObject e,double now,boolean active,boolean event,long wall){
        if(!AlertPolicy.eligible(e,now,active)||!e.optString("code").matches("[0-9]{4,6}[A-Z]?"))return false;
        try{
            SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));String day=fmt.format(new Date(wall));
            JSONObject receipts=day.equals(prefs.getString("alert_day",""))?new JSONObject(prefs.getString("alert_receipts","{}")):new JSONObject();
            String code=e.getString("code");JSONObject previous=receipts.optJSONObject(code);int level=e.getInt("matched");boolean confirmed=e.optBoolean("confirmed");double ts=e.getDouble("ts");
            if(previous!=null){
                int prior=previous.optInt("level");boolean upgrade=level>prior||level==prior&&confirmed&&!previous.optBoolean("confirmed");
                if(ts<previous.optDouble("ts"))return false;
                if(!upgrade){
                    if(ts==previous.optDouble("ts")||level<prior||!event)return false;
                    long age=wall-previous.optLong("wall");if(age<60000)return false;
                }
            }
            if(receipts.length()>=500&&previous==null){String oldest=null;long time=Long.MAX_VALUE;Iterator<String> keys=receipts.keys();while(keys.hasNext()){String k=keys.next();long t=receipts.getJSONObject(k).optLong("wall");if(t<time){oldest=k;time=t;}}if(oldest!=null)receipts.remove(oldest);}
            receipts.put(code,new JSONObject().put("level",level).put("confirmed",confirmed).put("ts",ts).put("wall",wall));
            prefs.edit().putString("alert_day",day).putString("alert_receipts",receipts.toString()).putString("last_signal_title",AlertPolicy.title(e)).putLong("last_signal_time",wall).apply();
            return true;
        }catch(JSONException error){return false;}
    }
}
