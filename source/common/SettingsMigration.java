package tw.rex.link;
import android.content.SharedPreferences;import org.json.*;
public final class SettingsMigration {
 public static void upgrade(SharedPreferences p){if(p.getInt("curriculumSchema",0)>=2)return;try{int old=p.getInt("subject",2);int subject=p.contains("subject")?(old==0?3:old==1?0:6):6;JSONArray custom=new JSONArray(p.getString("custom","[]"));for(int i=0;i<custom.length();i++){JSONObject q=custom.getJSONObject(i);if(!q.has("grade")){q.put("grade",1);q.put("subject",q.optInt("subject")==0?3:0);}}
 JSONArray topics=new JSONArray(p.getString("topics","[\"letters\",\"words\",\"zhuyin\",\"language\",\"custom\"]"));topics.put("math");topics.put("life");topics.put("health");topics.put("logic");p.edit().putInt("subject",subject).putInt("grade",1).putString("custom",custom.toString()).putString("topics",topics.toString()).putBoolean("math",true).putBoolean("life",true).putBoolean("health",true).putBoolean("logic",true).putInt("curriculumSchema",2).commit();}catch(Exception e){}}
}
