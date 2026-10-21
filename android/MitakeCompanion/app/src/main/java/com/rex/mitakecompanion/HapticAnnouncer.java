package com.rex.mitakecompanion;

import android.content.*;
import android.media.AudioAttributes;
import android.os.*;
import org.json.JSONObject;

/** Direct notification-usage vibration, independent of the notification permission. */
public final class HapticAnnouncer implements AutoCloseable {
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final Handler main=new Handler(Looper.getMainLooper());
    private int pending;private boolean testing,closed;
    private final Runnable flush=this::flush;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    public HapticAnnouncer(Context c){prefs=BridgeService.prefs(c);vibrator=c.getSystemService(Vibrator.class);
        listener=(p,k)->{if("vibration_enabled".equals(k)&&!p.getBoolean(k,true))main.post(()->{pending=0;testing=false;main.removeCallbacks(flush);if(vibrator!=null)vibrator.cancel();});};
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }
    static long[] pattern(int level){return level==6?new long[]{0,300,150,300,150,600}:new long[]{0,220,160,220};}
    public void announce(JSONObject event,double now,boolean active){if(prefs.getBoolean("vibration_enabled",true)&&AlertPolicy.eligible(event,now,active))enqueue(event.optInt("matched"),false);}
    public void test(){enqueue(6,true);}
    private void enqueue(int level,boolean test){main.post(()->{if(closed)return;pending=Math.max(pending,level);testing|=test;main.removeCallbacks(flush);main.postDelayed(flush,120);});}
    private void flush(){
        int level=pending;boolean test=testing;pending=0;testing=false;
        if(closed||level==0||!test&&!prefs.getBoolean("vibration_enabled",true))return;
        if(vibrator==null||!vibrator.hasVibrator()){prefs.edit().putString("vibration_status","此裝置沒有可用的震動馬達").apply();return;}
        try{
            VibrationEffect effect=VibrationEffect.createWaveform(pattern(level),-1);
            if(Build.VERSION.SDK_INT>=33)vibrator.vibrate(effect,new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_NOTIFICATION).build());
            else vibrator.vibrate(effect,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
            prefs.edit().putString("vibration_status",(test?"測試震動":"新訊號震動")+"已送出；實際效果依手機與勿擾設定").putInt("last_vibration_level",level).putLong("last_vibration_time",System.currentTimeMillis()).apply();
            android.util.Log.i("MitakeAlert","vibration requested level="+level+" test="+test);
        }catch(RuntimeException e){prefs.edit().putString("vibration_status","震動未成功，請檢查手機震動設定").apply();}
    }
    @Override public void close(){closed=true;prefs.unregisterOnSharedPreferenceChangeListener(listener);main.removeCallbacksAndMessages(null);if(vibrator!=null)vibrator.cancel();}
}
