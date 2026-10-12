package com.rex.twboardingscanner.backtest

import android.content.Context
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object BacktestCompletionAlert {
    const val MESSAGE="歷史回測完成！"
    private val handler=Handler(Looper.getMainLooper())
    fun useVibration(ringerMode:Int,mediaVolume:Int,mediaMuted:Boolean)=
        ringerMode!=AudioManager.RINGER_MODE_NORMAL||mediaVolume<=0||mediaMuted
    fun vibrationPattern()=longArrayOf(0,220,180,220,180,220,180,220,180,220)
    @Synchronized internal fun claim(context:Context,id:String):Boolean {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val prefs=context.getSharedPreferences("backtest-completion-alerts",0)
        if(prefs.getBoolean(id,false))return false
        return prefs.edit().putBoolean(id,true).commit()
    }
    fun notify(context:Context,id:String){
        val c=context.applicationContext
        if(!claim(c,id))return
        handler.post {
            val audio=c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if(useVibration(audio.ringerMode,audio.getStreamVolume(AudioManager.STREAM_MUSIC),audio.isStreamMute(AudioManager.STREAM_MUSIC)))vibrate(c)
            else speak(c,id,audio)
        }
    }
    private fun vibrate(c:Context){
        runCatching{
            val v=if(Build.VERSION.SDK_INT>=31)(c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            if(v.hasVibrator())v.vibrate(VibrationEffect.createWaveform(vibrationPattern(),-1))
        }
    }
    private fun speak(c:Context,id:String,audio:AudioManager){
        var engine:TextToSpeech?=null
        var closed=false
        fun finish(){if(!closed){closed=true;engine?.stop();engine?.shutdown();engine=null}}
        val timeout=Runnable{if(!closed){vibrate(c);finish()}}
        runCatching {
            engine=TextToSpeech(c){status->handler.post {
                if(closed)return@post
                val tts=engine
                if(status!=TextToSpeech.SUCCESS||tts==null){vibrate(c);finish();return@post}
                // Respect a mute/volume change that happens during engine startup.
                if(useVibration(audio.ringerMode,audio.getStreamVolume(AudioManager.STREAM_MUSIC),audio.isStreamMute(AudioManager.STREAM_MUSIC))){vibrate(c);finish();return@post}
                val language=tts.setLanguage(Locale.TAIWAN)
                if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){vibrate(c);finish();return@post}
                tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(utteranceId:String){}
                    override fun onDone(utteranceId:String){handler.post{handler.removeCallbacks(timeout);finish()}}
                    @Deprecated("Legacy TTS callback") override fun onError(utteranceId:String){handler.post{handler.removeCallbacks(timeout);vibrate(c);finish()}}
                })
                val options=Bundle().apply{putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,AudioManager.STREAM_MUSIC)}
                if(tts.speak(MESSAGE,TextToSpeech.QUEUE_ADD,options,id)==TextToSpeech.ERROR){vibrate(c);finish()}
            }}
            handler.postDelayed(timeout,30_000)
        }.onFailure{vibrate(c);finish()}
    }
}
