package tw.rex.link;
import java.net.*;import org.json.*;
public final class UpdateRules {
 public static void validate(JSONObject info,String pkg)throws Exception{if(!pkg.equals(info.getString("packageName")))throw new Exception("更新套件不符");if(!info.getString("sha256").matches("[a-fA-F0-9]{64}"))throw new Exception("更新驗證資料不完整");URL u=new URL(info.getString("apkUrl"));if(!"https".equals(u.getProtocol())||!"raw.githubusercontent.com".equals(u.getHost())||!u.getPath().startsWith("/rex0966445518/stock-monitor-updates/learning-island-updates/"))throw new Exception("不允許的更新來源");long bytes=info.getLong("bytes");if(bytes<1||bytes>52428800||info.getLong("versionCode")<1)throw new Exception("更新大小或版本資料錯誤");}
}
