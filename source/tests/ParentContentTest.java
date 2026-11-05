import tw.rex.link.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;
public class ParentContentTest {
 static int n;static void ok(boolean v,String label){if(!v)throw new AssertionError(label);n++;System.out.println("PASS "+label);}
 public static void main(String[] args)throws Exception{
  try{new JSONArray("true");throw new AssertionError("legacy failure missing");}catch(JSONException e){ok(true,"non-array legacy JSON is rejected (exception wording varies by runtime)");}
  for(Object corrupt:new Object[]{true,false,"true","false",42,"not JSON",JSONObject.NULL}){
   Map<String,Object> v=new HashMap<String,Object>();v.put("custom",corrupt);v.put("pinHash","keep-pin");v.put("grade",2);v.putAll(ParentContent.migration(v));
   ok(ParentContent.questions(v).length()==0,"legacy non-array becomes usable empty questions: "+corrupt);
   ok(v.get("pinHash").equals("keep-pin")&&v.get("grade").equals(2),"repair preserves parent PIN and grade: "+corrupt);
   ok(ParentContent.migration(v).isEmpty(),"repair is idempotent: "+corrupt);
  }
  JSONObject q=new JSONObject().put("question","2 + 1 = ?").put("answer","3").put("options",new JSONArray().put("2").put("3").put("4")).put("grade",1).put("subject",1).put("topic","custom").put("explanation","");
  Map<String,Object> v=new HashMap<String,Object>();v.put("custom",new JSONArray().put(q).toString());v.put("math",false);v.putAll(ParentContent.migration(v));
  ok(ParentContent.questions(v).getJSONObject(0).getString("question").equals("2 + 1 = ?"),"valid legacy custom question preserved");
  ok(!ParentContent.enabled(v,"math"),"legacy topic disabled setting preserved");
  ok(ParentContent.enabled(v,"custom"),"question JSON is not read as a false toggle");
  String saved=(String)v.get(ParentContent.QUESTIONS);
  JSONArray built=new JSONArray(new String(Files.readAllBytes(Paths.get("tv-learning/assets/questions.json")),"UTF-8"));
  for(int round=0;round<4;round++){
   boolean toggle=round%2==0;v.put(ParentContent.topicKey("custom"),toggle);
   JSONArray topics=new JSONArray().put("math");if(toggle)topics.put("custom");
   JSONObject c=new JSONObject().put("grade",1).put("subject",1).put("seconds",10).put("limit",10).put("voice",true).put("topics",topics).put("custom",ParentContent.questions(v));
   JSONObject payload=new JSONObject().put("seconds",30).put("music",true).put("effects",true).put("requireHomeLock",false).put("config",c);
   JSONObject wire=new JSONObject(payload.toString());
   ok(Rules.validate(wire.getJSONObject("config"),built)>0,"repeated start yields valid config round "+round);
   ok(wire.getJSONObject("config").getJSONArray("custom").length()==1,"question list remains an array round "+round);
   ok(wire.getBoolean("music")&&wire.getJSONObject("config").getBoolean("voice")&&!wire.getBoolean("requireHomeLock"),"watch payload booleans remain booleans round "+round);
   ok(v.get(ParentContent.QUESTIONS).equals(saved)&&ParentContent.enabled(v,"custom")==toggle,"toggle never overwrites question content round "+round);
  }
  v.put(ParentContent.QUESTIONS,"[]");ok(ParentContent.questions(v).length()==0,"intentional deletion does not restore old legacy questions");
  v.put(ParentContent.QUESTIONS,true);ok(ParentContent.questions(v).length()==1,"invalid new storage can recover valid legacy JSON");
  Map<String,Object> fresh=new HashMap<String,Object>();fresh.put("custom",false);fresh.putAll(ParentContent.migration(fresh));ok(!ParentContent.enabled(fresh,"custom"),"legacy false custom checkbox preserved");
  ok(!ParentContent.topicKey("custom").equals(ParentContent.QUESTIONS)&&!ParentContent.topicKey("custom").equals("custom"),"topic and questions have separate storage keys");
  System.out.println(n+" checks passed");
 }
}
