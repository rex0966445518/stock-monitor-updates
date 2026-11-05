package tw.rex.learningtv;
import android.content.*;import android.content.res.AssetFileDescriptor;import android.media.*;import org.json.*;import java.io.*;
/** Bundled question recordings: no device TTS engine or network needed. */
final class OfflineVoice {
 final Context c;JSONObject index=new JSONObject();MediaPlayer player;int generation;
 OfflineVoice(Context context){c=context;try(InputStream in=c.getAssets().open("voice/index.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);index=new JSONObject(out.toString("UTF-8"));}catch(Exception e){android.util.Log.e("OfflineVoice","Voice index unavailable",e);}}
 boolean play(String text,Runnable fallback){stop();String path=index.optString(text,"");if(path.isEmpty())return false;final int token=generation;try(AssetFileDescriptor fd=c.getAssets().openFd(path)){
 MediaPlayer mp=new MediaPlayer();player=mp;mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());mp.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());float v=Math.max(0,Math.min(100,c.getSharedPreferences("control",0).getInt("quizVolume",50)))/100f;mp.setVolume(v,v);
 mp.setOnPreparedListener(p->{if(player==p&&generation==token)p.start();});mp.setOnCompletionListener(p->{if(player==p)stop();});mp.setOnErrorListener((p,w,x)->{if(player==p&&generation==token){stop();if(fallback!=null)fallback.run();}return true;});mp.prepareAsync();return true;
 }catch(Exception e){stop();return false;}}
 void stop(){generation++;MediaPlayer old=player;player=null;if(old!=null){try{old.reset();old.release();}catch(Exception ignored){}}}
 void close(){stop();}
}
