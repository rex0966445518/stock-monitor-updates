package com.rex.mitakecompanion;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;
public class BridgeService extends Service {
    public static volatile JSONObject latest;
    public static volatile long receivedAt;
    public static volatile String connection="尚未連線";
    public static volatile String connectionReason="";
    private ScheduledExecutorService executor;
    private SpeechAnnouncer speech;
    private HapticAnnouncer haptics;
    private SignalGate gate;
    private SharedPreferences prefs;
    public static final String RETRY="com.rex.mitakecompanion.RETRY";
    public static final String STOP="com.rex.mitakecompanion.STOP";
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("pairing",MODE_PRIVATE);}
    @Override public void onCreate(){super.onCreate();prefs=prefs(this);speech=new SpeechAnnouncer(this);haptics=new HapticAnnouncer(this);gate=new SignalGate(prefs);NotificationManager n=getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel("connection","電腦連線狀態",NotificationManager.IMPORTANCE_LOW));
        NotificationChannel signals=new NotificationChannel("signals","5/6 與 6/6 觀察提醒",NotificationManager.IMPORTANCE_HIGH);signals.enableVibration(true);n.createNotificationChannel(signals);
    }
    private PendingIntent open(){return PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification connectionNotification(String text){
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,BridgeService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"connection").setSmallIcon(com.rex.mitakecompanion.R.drawable.ic_notification).setContentTitle("漲停雷達 · 電腦連動").setContentText(text).setContentIntent(open()).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止連線",stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){prefs.edit().putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}
        if(!prefs.getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY;}
        startForeground(1,connectionNotification("正在連接電腦…"));
        if(executor==null){executor=Executors.newSingleThreadScheduledExecutor();executor.scheduleWithFixedDelay(this::poll,0,3,TimeUnit.SECONDS);}
        if(intent!=null&&RETRY.equals(intent.getAction())&&executor!=null)executor.execute(this::poll);
        return START_STICKY;
    }
    private void poll(){
        if(!prefs.getBoolean("enabled",false))return;
        try {
            Pairing pair=new Pairing(prefs.getString("address",""),prefs.getString("token",""));
            String session=prefs.getString("session","");long after=prefs.getLong("seq",0);
            JSONObject data=new BridgeClient().fetch(pair,after,session);
            NotificationManager manager=getSystemService(NotificationManager.class);
            JSONArray events=data.optJSONArray("events");
            boolean same=session.equals(data.optString("session"));
            if(events!=null)for(int i=0;i<events.length();i++){
                JSONObject e=events.getJSONObject(i);
                if(same&&e.optLong("seq")<=after)continue;
                alert(e,data.optDouble("server_time"),data.optBoolean("active"),true);
            }
            JSONArray rows=data.optJSONArray("rows");
            if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null)alert(row,data.optDouble("server_time"),data.optBoolean("active"),false);}
            prefs.edit().putString("session",data.optString("session")).putLong("seq",data.optLong("seq"))
                .putString("snapshot",data.toString()).apply();
            connectionReason="";prefs.edit().putLong("last_success",System.currentTimeMillis()).apply();
            latest=data;receivedAt=SystemClock.elapsedRealtime();connection=data.optBoolean("active")?"已連線 · 電腦掃描中":"已連線 · 電腦已暫停";
            if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)manager.notify(1,connectionNotification(connection));
        }catch(Exception error){connectionReason=ConnectionDiagnosis.reason(error);connection="連線中斷 · "+connectionReason;if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager.class).notify(1,connectionNotification(error.getMessage()!=null&&error.getMessage().contains("配對")?"配對失效，請重新掃碼":"無法連接電腦，3秒後重試"));}
    }
    private void alert(JSONObject e,double now,boolean active,boolean event){
        if(!gate.accept(e,now,active,event,System.currentTimeMillis()))return;
        if(haptics!=null)haptics.announce(e,now,active);
        if(speech!=null)speech.announce(e,now,active);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            Notification notification=new androidx.core.app.NotificationCompat.Builder(this,"signals").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(AlertPolicy.title(e)).setContentText(AlertPolicy.detail(e))
                .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(AlertPolicy.detail(e)))
                .setContentIntent(open()).setAutoCancel(true).setShowWhen(true).setSilent(true).build();
            getSystemService(NotificationManager.class).notify("candidate-"+e.optString("code"),2,notification);
        }catch(RuntimeException ignored){}
    }
    @Override public void onDestroy(){if(speech!=null)speech.close();speech=null;if(haptics!=null)haptics.close();haptics=null;if(executor!=null)executor.shutdownNow();executor=null;connection="已停止連線";receivedAt=0;super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
