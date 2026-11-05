package tw.rex.link;
import org.json.JSONArray;
import java.util.*;
/** Parent-only storage: a topic toggle must never overwrite question JSON. */
public final class ParentContent {
 public static final String QUESTIONS="customQuestions";
 private static final String[] TOPICS={"letters","words","zhuyin","language","math","life","health","logic","custom"};
 public static String topicKey(String topic){return "topicEnabled."+topic;}
 private static JSONArray array(Object value){if(!(value instanceof String))return null;try{return new JSONArray((String)value);}catch(Exception e){return null;}}
 public static JSONArray questions(Map<String,?> values){JSONArray current=array(values.get(QUESTIONS));if(current!=null)return current;JSONArray legacy=array(values.get("custom"));return legacy!=null?legacy:new JSONArray();}
 public static boolean enabled(Map<String,?> values,String topic){String key=topicKey(topic);return ParentForm.bool(values.containsKey(key)?values.get(key):values.get(topic),true);}
 public static Map<String,Object> migration(Map<String,?> values){Map<String,Object> writes=new LinkedHashMap<String,Object>();if(array(values.get(QUESTIONS))==null)writes.put(QUESTIONS,questions(values).toString());for(String topic:TOPICS)if(!values.containsKey(topicKey(topic)))writes.put(topicKey(topic),enabled(values,topic));return writes;}
}
