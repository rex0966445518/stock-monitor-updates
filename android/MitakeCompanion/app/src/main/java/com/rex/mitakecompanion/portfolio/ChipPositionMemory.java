package com.rex.mitakecompanion.portfolio;

import android.content.*;
import android.graphics.Rect;
import org.json.*;
import java.util.*;

/** Bounded local anchors: used only to crop OCR, never to blindly tap old coordinates. */
final class ChipPositionMemory {
    private final SharedPreferences prefs;private final String version;
    ChipPositionMemory(Context context){prefs=context.getSharedPreferences("chip_position_learning",0);String v="unknown";try{v=String.valueOf(context.getPackageManager().getPackageInfo(ChipCaptureService.PACKAGE,0).versionName);}catch(Exception ignored){}version=v+"-font"+Math.round(context.getResources().getConfiguration().fontScale*100);}
    private String key(String page,int width,int height){return "anchor-"+version+"-"+(width>height?"L":"P")+"-"+(width/80)+"x"+(height/80)+"-"+page;}
    synchronized Rect hint(String page,int width,int height){try{JSONObject v=new JSONObject(prefs.getString(key(page,width,height),"{}"));if(v.optInt("confirmed")<2)return null;Rect b=new Rect((int)(v.getDouble("l")*width),(int)(v.getDouble("t")*height),(int)(v.getDouble("r")*width),(int)(v.getDouble("b")*height));if(b.top<height*.13||b.bottom>height*.50||b.isEmpty())return null;b.inset(-Math.max(20,width/30),-Math.max(12,height/80));b.intersect(0,0,width,height);return b;}catch(Exception e){return null;}}
    synchronized void confirm(String page,Rect anchor,int width,int height,String recognized,String code){
        if(anchor==null||anchor.isEmpty()||!ChipVideoProfile.bodyMatches(recognized,page)||!ChipCaptureService.exactCode(recognized,code)||anchor.top<height*.13||anchor.bottom>height*.50)return;
        try{String key=key(page,width,height);JSONObject old=new JSONObject(prefs.getString(key,"{}"));JSONObject v=new JSONObject().put("l",(double)anchor.left/width).put("t",(double)anchor.top/height).put("r",(double)anchor.right/width).put("b",(double)anchor.bottom/height).put("confirmed",Math.min(20,old.optInt("confirmed")+1)).put("misses",0);
            SharedPreferences.Editor edit=prefs.edit().putString(key,v.toString());if(prefs.getAll().size()>70)for(String k:prefs.getAll().keySet())if(k.startsWith("anchor-")&&!k.equals(key))edit.remove(k);edit.apply();
        }catch(Exception ignored){}
    }
    synchronized void miss(String page,int width,int height){String key=key(page,width,height);try{JSONObject v=new JSONObject(prefs.getString(key,"{}"));if(!v.has("confirmed"))return;int misses=v.optInt("misses")+1;if(misses>=2)prefs.edit().remove(key).apply();else{v.put("misses",misses);prefs.edit().putString(key,v.toString()).apply();}}catch(Exception ignored){}}
    synchronized void count(String key){prefs.edit().putInt(key,prefs.getInt(key,0)+1).apply();}
    synchronized String summary(){int anchors=0;for(String key:prefs.getAll().keySet())if(key.startsWith("anchor-"))anchors++;return "已學習位置 "+anchors+" · 快速定位 "+prefs.getInt("fast_hits",0)+"次 · 放大辨識 "+prefs.getInt("zoom_reads",0)+"次";}
    synchronized void clear(){prefs.edit().clear().apply();}
}
