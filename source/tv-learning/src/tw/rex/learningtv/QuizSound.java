package tw.rex.learningtv;
import android.content.*;import android.content.res.AssetFileDescriptor;import android.media.*;import java.util.*;
final class QuizSound {
 final Context c;final android.content.SharedPreferences p;SoundPool pool;MediaPlayer loop;int[] correct=new int[10];int wrong;Set<Integer> loaded=new HashSet<Integer>();Random random=new Random();
 QuizSound(Context context,android.content.SharedPreferences prefs){c=context;p=prefs;pool=new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();pool.setOnLoadCompleteListener((sp,id,status)->{if(status==0)loaded.add(id);});for(int i=0;i<10;i++)correct[i]=load("correct_"+(i+1));wrong=load("wrong");}
 int load(String name){try(AssetFileDescriptor fd=c.getAssets().openFd("sounds/"+name+".ogg")){return pool.load(fd,1);}catch(Exception e){return 0;}}
 float volume(){return Math.max(0,Math.min(100,p.getInt("quizVolume",50)))/100f;}
 void startHomeLoop(){startAssetLoop("sounds/home_loop.ogg",.32f);}
 void startLoop(){if(!p.getBoolean("quizMusic",true))return;startAssetLoop("sounds/quiz_loop.ogg",.4f);}
 void startAssetLoop(String path,float gain){if(loop!=null)return;try(AssetFileDescriptor fd=c.getAssets().openFd(path)){loop=new MediaPlayer();loop.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());loop.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());loop.setLooping(true);loop.setVolume(volume()*gain,volume()*gain);loop.prepare();loop.start();}catch(Exception e){stopLoop();}}
 void stopLoop(){if(loop!=null){try{loop.stop();}catch(Exception e){}loop.release();loop=null;}}
 void correct(){stopLoop();effect(correct[random.nextInt(correct.length)]);}
 void wrong(){effect(wrong);}
 void effect(int id){if(p.getBoolean("quizEffects",true)&&loaded.contains(id))pool.play(id,volume(),volume(),1,0,1f);}
 void close(){stopLoop();pool.release();}
}
