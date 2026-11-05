package tw.rex.link;
import java.util.*;import org.json.*;
public final class QuestionCatalog {
 public static final String DEFAULT_LEVELS="[\"easy\",\"starter\",\"intermediate\",\"advanced\"]";
 public static Set<String> levels(JSONArray items)throws Exception{Set<String> out=new HashSet<String>();JSONArray values=items==null?new JSONArray(DEFAULT_LEVELS):items;for(int i=0;i<values.length();i++){String value=values.getString(i);if(!Arrays.asList("easy","starter","intermediate","advanced").contains(value))throw new Exception("不支援的智力題難度");out.add(value);}return out;}
 public static boolean matches(JSONObject q,int subject,int grade,Set<String> topics,Set<String> levels){String topic=q.optString("topic");boolean logic=topic.equals("logic");boolean gradeMatches=q.optInt("grade",1)==grade||(logic&&q.optInt("grade",1)==0);return(subject==6||q.optInt("subject")==subject)&&gradeMatches&&topics.contains(topic)&&(!logic||levels.contains(q.optString("difficulty","easy")));}
 public static int nextLogicIndex(JSONArray bank,List<Integer> eligible,int cursor){int chosen=-1,first=-1;int nextNumber=Integer.MAX_VALUE,firstNumber=Integer.MAX_VALUE;for(int index:eligible){JSONObject q=bank.optJSONObject(index);if(q==null||!q.optString("topic").equals("logic"))continue;int number=q.optInt("logicNumber",Integer.MAX_VALUE);if(number<firstNumber){first=index;firstNumber=number;}if(number>cursor&&number<nextNumber){chosen=index;nextNumber=number;}}return chosen>=0?chosen:first;}
 public static String label(JSONObject q){if(!q.optString("topic").equals("logic"))return "";return "智力測驗 · "+q.optString("difficultyLabel","簡單")+" · 第 "+q.optInt("logicNumber",1)+" / 100 題";}
}
