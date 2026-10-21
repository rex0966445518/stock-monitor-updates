package com.rex.mitakecompanion.portfolio;

import java.util.*;
import java.util.regex.Pattern;

/** Navigation/body anchors transcribed from the user's 720x1600 recording.
 * Runtime coordinates always come from visible nodes, never saved video pixels. */
final class ChipVideoProfile {
    private static final List<String> ORDER=Arrays.asList("即時","討論","K線","籌碼日報","達人觀點","法人","多空","主力","新聞","健檢","大戶","法人目標","營收");
    static String clean(String text){return text.replaceAll("[\\s\u3000]+","").replace("筹","籌").replace("码","碼").replace("报","報").replace("线","線").replace("綫","線").replace("Ｋ","K").replace("户","戶").replace("动","動").replace("买","買").replace("卖","賣").replace("资","資").replace("数","數").replace("张","張").replace("势","勢").replace("间","間").replace("转","轉").replace("还","還").replace("归","歸");}
    static boolean tabLabel(String text,String page){String a=clean(text).toUpperCase(Locale.ROOT),b=clean(page).toUpperCase(Locale.ROOT);if(a.equals(b))return true;if(b.length()<4||a.length()!=b.length())return false;int diff=0;for(int i=0;i<a.length();i++)if(a.charAt(i)!=b.charAt(i))diff++;return diff==1;}
    static int order(String text){for(int i=0;i<ORDER.size();i++)if(tabLabel(text,ORDER.get(i)))return i;return -1;}
    static String canonical(String text){int i=order(text);return i<0?"":ORDER.get(i);}
    static boolean scrollForward(String target,List<String> visible){
        int first=Integer.MAX_VALUE;for(String s:visible){int i=order(s);if(i>=0)first=Math.min(first,i);}return order(target)>=first;
    }
    static boolean stockTitle(String text){return Pattern.compile("^[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z .・-]{0,16}\\s*[0-9]{4,6}[A-Za-z]?$",Pattern.UNICODE_CASE).matcher(text.trim()).matches();}
    static boolean stockResult(String text,String code){String s=text.trim();return s.equals(code)||s.matches(Pattern.quote(code)+"\\s*[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z .・-]{0,16}");}
    static String[] options(String page){
        switch(page){case "K線":return new String[]{"日"};case "籌碼日報":return new String[]{"1"};case "法人":return new String[]{"三大法人"};case "主力":return new String[]{"主力買賣超"};case "大戶":return new String[]{"600","200","張數比例"};default:return new String[0];}
    }
    private static boolean any(String s,String... values){for(String value:values)if(s.contains(value))return true;return false;}
    static int bodyScore(String text,String page){
        String s=clean(text).toUpperCase(Locale.ROOT).replace("TOPI5","TOP15").replace("TOPL5","TOP15").replace("T0P","TOP");int score=0;
        switch(page){
            case "K線":if(!any(s,"MACD","日分價量"))return 0;return (s.contains("MACD")?3:0)+(s.contains("成交量")?1:0)+(s.contains("還原日")?2:0)+(s.contains("日分價量")?2:0);
            case "籌碼日報":if(!any(s,"主力動向","買方TOP","賣方TOP"))return 0;return (s.contains("主力動向")?3:0)+(s.contains("買方TOP")?3:0)+(s.contains("賣方TOP")?3:0)+(any(s,"區間週轉率","區間周轉率")?2:0)+(s.contains("籌碼集中")?1:0)+(s.contains("成交量")?1:0);
            case "法人":if(!s.contains("三大法人"))return 0;return (s.contains("三大法人買賣超")?4:3)+(s.contains("外資")?1:0)+(s.contains("投信")?1:0)+(s.contains("自營商")?1:0)+(s.contains("八大行庫")?2:0);
            case "多空":if(!any(s,"多方優勢","空方優勢"))return 0;return 4+(s.contains("符合")?1:0)+(s.contains("籌碼面")?2:0)+(s.contains("價量面")?2:0);
            case "主力":if(!any(s,"主力買賣超","買賣家數差"))return 0;return (s.contains("主力買賣超")?3:0)+(s.contains("買賣家數差")?3:0)+(s.contains("20日集中")?2:0)+(s.contains("5日集中")?2:0);
            case "大戶":if(!s.contains("大戶持股")||!s.contains("散戶持股"))return 0;return 6+(s.contains("張數比例")?1:0)+(s.contains("大戶增減")?2:0)+(s.contains("持股人數")?1:0);
            default:return 0;
        }
    }
    static String detectedPage(String text){String found="";for(String page:PortfolioStore.PAGES){int threshold=page.equals("K線")?4:page.equals("多空")||page.equals("大戶")?6:5;if(bodyScore(text,page)>=threshold){if(!found.isEmpty())return "";found=page;}}return found;}
    static boolean bodyMatches(String text,String page){return page.equals(detectedPage(text));}
}
