package com.rex.mitakecompanion;

import android.content.*;
import android.media.AudioAttributes;
import android.os.*;
import android.speech.tts.*;
import org.json.JSONObject;
import java.util.*;

/** Bounded, expiring speech queue. A 6/6 upgrade replaces a queued 5/6. */
public final class SpeechAnnouncer implements AutoCloseable {
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String,Utterance> pending=new LinkedHashMap<>();
    private TextToSpeech tts;
    private boolean ready,busy,closed;
    private long serial;
    private String speakingId="";
    private final Runnable watchdog=()->{
        if(!busy||closed)return;busy=false;speakingId="";
        if(tts!=null)try{tts.stop();}catch(RuntimeException ignored){}
        state("語音引擎回報逾時，已繼續處理新訊號");pump();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener listener=(p,k)->{
        if("voice_enabled".equals(k)&&!p.getBoolean(k,true))main.post(()->{pending.clear();busy=false;speakingId="";main.removeCallbacks(watchdog);if(tts!=null)tts.stop();});
    };
    static final class Utterance {
        final String text;final long expires;final boolean test;
        Utterance(String text,long expires,boolean test){this.text=text;this.expires=expires;this.test=test;}
    }
    public SpeechAnnouncer(Context c){context=c.getApplicationContext();prefs=BridgeService.prefs(c);prefs.registerOnSharedPreferenceChangeListener(listener);
        state("語音初始化中");
        try{tts=new TextToSpeech(context,status->main.post(()->initialize(status)));}
        catch(RuntimeException e){state("語音引擎無法啟動，請檢查手機語音設定");}
    }
    private void state(String text){prefs.edit().putString("voice_status",text).apply();}
    private void initialize(int status){
        if(closed||tts==null)return;
        if(status!=TextToSpeech.SUCCESS){state("語音引擎無法啟動，請檢查手機語音設定");return;}
        int language=tts.setLanguage(Locale.TAIWAN);
        if(language<0)language=tts.setLanguage(Locale.TRADITIONAL_CHINESE);
        if(language<0){state("缺少中文語音，請在手機語音設定下載中文語音");return;}
        tts.setSpeechRate(1.0f);
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){}
            public void onDone(String id){main.post(()->{if(!id.equals(speakingId))return;busy=false;speakingId="";main.removeCallbacks(watchdog);pump();});}
            public void onError(String id){main.post(()->{if(!id.equals(speakingId))return;busy=false;speakingId="";main.removeCallbacks(watchdog);state("語音播放失敗，請檢查媒體音量與中文語音");pump();});}
        });
        ready=true;state("中文語音已就緒");pump();
    }
    static String phrase(JSONObject e){
        return e.optString("name","股名待補")+"，股號 "+e.optString("code").replace("", " ").trim()+"，"+(e.optInt("matched")==6?"六項基礎條件符合。":"五項條件符合，接近候選請注意。")+
            (e.optBoolean("confirmed")?"必要檢核已通過，請注意風險。":"觀察候選，必要檢核待確認。");
    }
    public void announce(JSONObject event,double now,boolean active){
        if(!prefs.getBoolean("voice_enabled",true)||!AlertPolicy.eligible(event,now,active))return;
        String code=event.optString("code");String words=phrase(event);
        long expiry=SystemClock.elapsedRealtime()+(long)((15-(now-event.optDouble("ts")))*1000);
        main.post(()->enqueue(code,new Utterance(words,expiry,false)));
    }
    public void test(){main.post(()->enqueue("test",new Utterance("漲停雷達語音測試。五項與六項條件有新訊號時，會在此通報。",SystemClock.elapsedRealtime()+30000,true)));}
    private void enqueue(String key,Utterance value){if(closed)return;pending.put(key,value);while(pending.size()>3)pending.remove(pending.keySet().iterator().next());pump();}
    private void pump(){
        if(closed||!ready||busy)return;
        Iterator<Map.Entry<String,Utterance>> it=pending.entrySet().iterator();
        while(it.hasNext()){
            Utterance u=it.next().getValue();it.remove();
            if(u.expires<SystemClock.elapsedRealtime()||!u.test&&!prefs.getBoolean("voice_enabled",true))continue;
            busy=true;
            speakingId="signal-"+(++serial);
            try{
                if(tts.speak(u.text,TextToSpeech.QUEUE_FLUSH,null,speakingId)==TextToSpeech.ERROR){busy=false;speakingId="";state("語音播放失敗，請檢查手機語音設定");continue;}
                main.postDelayed(watchdog,20000);android.util.Log.i("MitakeAlert","voice request submitted");break;
            }catch(RuntimeException e){busy=false;speakingId="";state("語音引擎中斷，請檢查手機語音設定");}
        }
    }
    @Override public void close(){closed=true;prefs.unregisterOnSharedPreferenceChangeListener(listener);main.removeCallbacksAndMessages(null);pending.clear();if(tts!=null){tts.stop();tts.shutdown();tts=null;}}
}
