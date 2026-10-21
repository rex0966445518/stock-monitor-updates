package com.rex.mitakecompanion;

import android.content.SharedPreferences;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Phone-owned daily records. Snapshot omissions and PC restarts never delete them. */
final class SignalHistory {
    private static String day(double ts){
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
        return format.format(new Date((long)(ts*1000)));
    }
    static String key(JSONObject row){return day(row.optDouble("ts"))+":"+row.optString("code");}
    static synchronized JSONArray merge(SharedPreferences prefs,JSONObject snapshot){
        Map<String,JSONObject> saved=new HashMap<>();
        try{
            JSONArray previous=new JSONArray(prefs.getString("signal_history","[]"));
            for(int i=0;i<previous.length();i++){JSONObject row=previous.optJSONObject(i);if(row!=null)saved.put(key(row),row);}
        }catch(JSONException ignored){}
        Set<String> current=new HashSet<>();
        if(snapshot!=null){
            // Events can contain a 5/6 signal that has already weakened in the same poll.
            absorb(saved,snapshot.optJSONArray("events"),null);
            absorb(saved,snapshot.optJSONArray("rows"),current);
        }
        ArrayList<JSONObject> sorted=new ArrayList<>(saved.values());
        sorted.sort((a,b)->Double.compare(b.optDouble("ts"),a.optDouble("ts")));
        JSONArray disk=new JSONArray(),display=new JSONArray();
        for(JSONObject row:sorted)try{
            disk.put(row);
            JSONObject copy=new JSONObject(row.toString());
            copy.put("history_key",key(row)).put("history_date",day(row.optDouble("ts")))
                .put("history_missing",!current.contains(key(row)));
            display.put(copy);
        }catch(JSONException ignored){}
        String encoded=disk.toString();
        if(!encoded.equals(prefs.getString("signal_history","[]")))prefs.edit().putString("signal_history",encoded).apply();
        return display;
    }
    private static void absorb(Map<String,JSONObject> saved,JSONArray rows,Set<String> current){
        if(rows==null)return;
        for(int i=0;i<rows.length();i++)try{
            JSONObject input=rows.optJSONObject(i);
            if(input==null||input.optBoolean("demo")||!IntradayData.validCode(input.optString("code"))||input.optDouble("ts")<=0)continue;
            int count=input.optInt("matched");if(count<0||count>6)continue;
            String id=key(input);JSONObject old=saved.get(id);
            int peak=Math.max(count,Math.max(input.optInt("peak"),old==null?0:old.optInt("peak")));
            if(peak<5)continue;
            if(current!=null&& (old==null||input.optDouble("ts")>=old.optDouble("ts")))current.add(id);
            if(old!=null&&input.optDouble("ts")<old.optDouble("ts")){
                old.put("peak",peak);continue;
            }
            JSONObject row=new JSONObject(input.toString());
            row.remove("history_missing");row.remove("history_key");row.remove("history_date");
            row.put("peak",peak);
            saved.put(id,row);
        }catch(JSONException ignored){}
    }
}
