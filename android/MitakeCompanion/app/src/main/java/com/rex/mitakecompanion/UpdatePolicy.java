package com.rex.mitakecompanion;

import org.json.*;
import java.util.regex.Pattern;

/** Accept only published companion releases from the known repository. */
public final class UpdatePolicy {
    static final String ROOT="https://github.com/rex0966445518/stock-monitor-updates/releases/download/";
    static final String API="https://api.github.com/repos/rex0966445518/stock-monitor-updates/releases?per_page=100";
    static final long MAX_APK=50L*1024*1024;
    static final class Release {
        final String version,url,checksumUrl,digest;final long size;
        Release(String v,long s,String digest){version=v;size=s;this.digest=digest;String base=ROOT+"mitake-companion-v"+v+"/";url=base+"MitakeCompanion-v"+v+".apk";checksumUrl=base+"MitakeCompanion-SHA256SUMS.txt";}
    }
    static int compare(String a,String b){String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<3;i++){int d=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(d!=0)return d;}return 0;}
    static Release newest(JSONArray all,String current)throws JSONException{
        Release selected=null;
        for(int i=0;i<all.length();i++){
            JSONObject r=all.getJSONObject(i);String tag=r.optString("tag_name");
            if(r.optBoolean("draft")||r.optBoolean("prerelease")||!tag.matches("mitake-companion-v[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}"))continue;
            String v=tag.substring("mitake-companion-v".length());
            if(compare(v,current)<=0||selected!=null&&compare(v,selected.version)<=0)continue;
            JSONArray assets=r.optJSONArray("assets");if(assets==null)continue;
            for(int j=0;j<assets.length();j++){
                JSONObject a=assets.getJSONObject(j);String expected="MitakeCompanion-v"+v+".apk";long size=a.optLong("size");
                if(expected.equals(a.optString("name"))&&(ROOT+tag+"/"+expected).equals(a.optString("browser_download_url"))&&size>0&&size<=MAX_APK){
                    String digest=a.optString("digest","");if(digest.startsWith("sha256:"))digest=digest.substring(7);else digest="";
                    if(!digest.isEmpty()&&!digest.matches("[a-fA-F0-9]{64}"))continue;
                    selected=new Release(v,size,digest.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return selected;
    }
    static String checksum(String text,String filename){
        for(String line:text.split("\\r?\\n")){String[] parts=line.trim().split("\\s+",2);if(parts.length==2&&parts[0].matches("[a-fA-F0-9]{64}")&&parts[1].replaceFirst("^\\*","").equals(filename))return parts[0].toLowerCase(java.util.Locale.ROOT);}
        throw new IllegalArgumentException("更新檔缺少正確校驗碼，請稍後重試");
    }
}
