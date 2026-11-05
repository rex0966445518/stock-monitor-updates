package tw.rex.learningtv;
import android.content.*;import android.media.*;import android.os.*;import android.view.KeyEvent;
final class MediaGate {
 final AudioManager audio;final android.content.SharedPreferences p;boolean requested=false;AudioFocusRequest focus;String info="尚未發送影音控制";
 final AudioManager.OnAudioFocusChangeListener listener=change->{};
 MediaGate(Context context,android.content.SharedPreferences prefs){audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);p=prefs;}
 void key(int code){long now=SystemClock.uptimeMillis();audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0));audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,code,0));}
 void pause(){if(requested)return;requested=true;try{key(KeyEvent.KEYCODE_MEDIA_PAUSE);p.edit().putBoolean("mediaPauseSent",true).apply();info="已送出暫停指令；需播放器支援";}catch(Exception e){info="播放器暫停指令未送達";}try{int result;if(Build.VERSION.SDK_INT>=26){focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener(listener).build();result=audio.requestAudioFocus(focus);}else result=audio.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);if(result!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)info+="；音訊焦點未取得";}catch(Exception e){info+="；音訊焦點受系統限制";}}
 void resume(){releaseFocus();if(p.getBoolean("mediaPauseSent",false))try{key(KeyEvent.KEYCODE_MEDIA_PLAY);info="已送出繼續播放指令；需播放器支援";}catch(Exception e){info="繼續播放未送達，請按播放器播放鍵";}p.edit().putBoolean("mediaPauseSent",false).apply();}
 void releaseFocus(){if(!requested)return;try{if(Build.VERSION.SDK_INT>=26&&focus!=null)audio.abandonAudioFocusRequest(focus);else audio.abandonAudioFocus(listener);}catch(Exception e){}requested=false;}
}
