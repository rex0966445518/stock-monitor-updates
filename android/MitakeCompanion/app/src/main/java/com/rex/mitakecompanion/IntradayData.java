package com.rex.mitakecompanion;
import org.json.*;
import java.util.*;

/** Unmodified five-minute OHLC bars from a separate quote source. */
public final class IntradayData {
    static final class Bar {
        final long time;final double open,high,low,close,volume;
        Bar(long t,double o,double h,double l,double c,double v){time=t;open=o;high=h;low=l;close=c;volume=v;}
    }
    final String symbol;final List<Bar> bars;
    IntradayData(String s,List<Bar> b){symbol=s;bars=Collections.unmodifiableList(b);}
    static boolean validCode(String code){return code!=null&&code.matches("[0-9]{4,6}");}
    static IntradayData parse(String body,String expected)throws Exception{
        JSONObject chart=new JSONObject(body).getJSONObject("chart");
        if(!chart.isNull("error"))throw new IllegalArgumentException("來源暫無此股票五分K");
        JSONArray results=chart.optJSONArray("result");if(results==null||results.length()==0)throw new IllegalArgumentException("來源未提供五分K");
        JSONObject row=results.getJSONObject(0),meta=row.getJSONObject("meta");
        if(!expected.equals(meta.optString("symbol"))||!"5m".equals(meta.optString("dataGranularity")))throw new IllegalArgumentException("股票或K線週期不符，已停止顯示");
        JSONArray times=row.getJSONArray("timestamp");JSONObject q=row.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray opens=q.getJSONArray("open"),highs=q.getJSONArray("high"),lows=q.getJSONArray("low"),closes=q.getJSONArray("close"),volumes=q.optJSONArray("volume");
        TreeMap<Long,Bar> valid=new TreeMap<>();
        for(int i=0;i<times.length();i++){
            long t=times.optLong(i,0);double o=opens.optDouble(i,Double.NaN),h=highs.optDouble(i,Double.NaN),l=lows.optDouble(i,Double.NaN),c=closes.optDouble(i,Double.NaN),v=volumes==null?Double.NaN:volumes.optDouble(i,Double.NaN);
            if(t<=0||t>System.currentTimeMillis()/1000+60||!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)||o<=0||c<=0||l<=0||h<Math.max(o,c)||l>Math.min(o,c)||h<l)continue;
            valid.put(t,new Bar(t,o,h,l,c,Double.isFinite(v)&&v>=0?v:Double.NaN));
        }
        if(valid.isEmpty())throw new IllegalArgumentException("來源資料不足，無有效五分K");
        // One most recent Taiwan trading day. Never join days as if they were continuous.
        java.text.SimpleDateFormat day=new java.text.SimpleDateFormat("yyyyMMdd",Locale.US);day.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
        String last=day.format(new Date(valid.lastKey()*1000));List<Bar> bars=new ArrayList<>();
        for(Bar b:valid.values())if(day.format(new Date(b.time*1000)).equals(last))bars.add(b);
        return new IntradayData(expected,bars);
    }
}
