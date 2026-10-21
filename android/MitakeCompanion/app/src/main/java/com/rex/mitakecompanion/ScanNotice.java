package com.rex.mitakecompanion;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.*;

/** Operational warnings are separate from candidate events and their speech/history. */
public final class ScanNotice {
    static boolean eligible(JSONObject e,double now,boolean same,long after){
        double ts=e.optDouble("ts",Double.NaN);
        return "ranking_skipped".equals(e.optString("kind")) && !e.optString("ranking").isEmpty()
            && e.optLong("seq")>0 && (!same||e.optLong("seq")>after)
            && Double.isFinite(now) && Double.isFinite(ts) && now>=ts && now-ts<=300;
    }
    public static void notify(Context context,JSONObject data,String session,long after){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        JSONArray notices=data.optJSONArray("notices");if(notices==null)return;
        for(int i=0;i<notices.length();i++){
            JSONObject e=notices.optJSONObject(i);
            if(e==null||!eligible(e,data.optDouble("server_time",Double.NaN),session.equals(data.optString("session")),after))continue;
            String rank=e.optString("ranking"),detail="第"+e.optInt("cycle")+"輪："+e.optString("reason")+"。已跳過並繼續其他排行，下一輪再試。";
            PendingIntent open=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(context,"scan_errors").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("排行已跳過 · "+rank).setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).build();
            context.getSystemService(NotificationManager.class).notify("scan-"+rank,3,n);
        }
    }
}
