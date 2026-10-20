package com.rex.mitakecompanion;
import org.json.JSONObject;
public final class AlertPolicy {
    public static boolean eligible(JSONObject event,double serverNow,boolean active) {
        int count=event.optInt("matched");double age=serverNow-event.optDouble("ts",0);
        return active&&event.optBoolean("valid")&&event.optInt("total")==6&&count>=5&&count<=6&&age>=0&&age<=15;
    }
    public static String title(JSONObject event) {
        String name=event.optString("name","");if(name.isEmpty())name="股名待補";
        return event.optInt("matched")+"/6 · "+name+" "+event.optString("code");
    }
    public static String detail(JSONObject event) {
        return (event.optBoolean("confirmed")?"必要檢核已通過":"觀察候選，必要檢核仍待確認")+" · "+String.format(java.util.Locale.US,"%.2f 元",event.optDouble("price"))+"\n觀察訊號不代表一定漲停";
    }
}
