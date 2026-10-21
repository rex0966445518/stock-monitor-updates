package com.rex.mitakecompanion.portfolio;

import java.util.*;
import java.util.regex.Pattern;

/** Navigation/body anchors transcribed from the user's 720x1600 recording.
 * Runtime coordinates always come from visible nodes, never saved video pixels. */
final class ChipVideoProfile {
    private static final List<String> ORDER=Arrays.asList("即時","討論","K線","籌碼日報","達人觀點","法人","多空","主力","新聞","健檢","大戶","法人目標","營收");
    static String clean(String text){return text.replaceAll("[\\s\u3000]+","");}
    static int order(String text){return ORDER.indexOf(clean(text));}
    static boolean scrollForward(String target,List<String> visible){
        int first=Integer.MAX_VALUE;for(String s:visible){int i=order(s);if(i>=0)first=Math.min(first,i);}return order(target)>=first;
    }
    static boolean stockTitle(String text){return Pattern.compile("^[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z .・-]{0,16}\\s*[0-9]{4,6}[A-Za-z]?$",Pattern.UNICODE_CASE).matcher(text.trim()).matches();}
    static boolean stockResult(String text,String code){String s=text.trim();return s.equals(code)||s.matches(Pattern.quote(code)+"\\s+[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z .・-]{0,16}");}
    static String[] options(String page){
        switch(page){case "K線":return new String[]{"日"};case "籌碼日報":return new String[]{"1"};case "法人":return new String[]{"三大法人"};case "主力":return new String[]{"主力買賣超"};case "大戶":return new String[]{"600","200","張數比例"};default:return new String[0];}
    }
    static boolean bodyMatches(String text,String page){
        String s=clean(text).toUpperCase(Locale.ROOT);
        switch(page){
            case "K線":return s.contains("MACD")&&s.contains("成交量")&&s.contains("還原日");
            case "籌碼日報":return s.contains("主力動向")&&s.contains("籌碼集中")&&s.contains("買方TOP15");
            case "法人":return s.contains("三大法人買賣超")&&s.contains("外資")&&s.contains("投信");
            case "多空":return (s.contains("多方優勢")||s.contains("空方優勢"))&&s.contains("符合")&&s.contains("籌碼面");
            case "主力":return s.contains("主力買賣超")&&s.contains("買賣家數差")&&s.contains("20日集中");
            case "大戶":return s.contains("大戶持股")&&s.contains("散戶持股")&&s.contains("張數比例");
            default:return false;
        }
    }
}
