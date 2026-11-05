package tw.rex.link;
public final class ParentForm {
 public static int integer(Object value,int fallback){try{if(value instanceof Number)return ((Number)value).intValue();if(value instanceof String)return Integer.parseInt(((String)value).trim());}catch(RuntimeException ignored){}return fallback;}
 public static long longValue(Object value,long fallback){try{if(value instanceof Number)return ((Number)value).longValue();if(value instanceof String)return Long.parseLong(((String)value).trim());}catch(RuntimeException ignored){}return fallback;}
 public static boolean bool(Object value,boolean fallback){if(value instanceof Boolean)return (Boolean)value;if(value instanceof Number)return ((Number)value).intValue()!=0;if(value instanceof String){String s=((String)value).trim();if(s.equalsIgnoreCase("true")||s.equals("1"))return true;if(s.equalsIgnoreCase("false")||s.equals("0"))return false;}return fallback;}
 public static String string(Object value,String fallback){if(value instanceof String)return (String)value;if(value instanceof Number||value instanceof Boolean)return String.valueOf(value);return fallback;}
 public static int selection(int index,int count){return count<=0?0:Math.min(Math.max(index,0),count-1);}
}
