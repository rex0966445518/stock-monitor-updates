package tw.rex.link;
import java.net.URI;
import java.net.URLDecoder;
/** Strict public YouTube video URL parsing, independent of Android UI. */
public final class YouTubeLink {
 private YouTubeLink(){}
 public static String videoId(String url){try{
  URI u=new URI(url);String host=u.getHost();
  if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return null;
  if(!("youtu.be".equals(host)||"youtube.com".equals(host)||"www.youtube.com".equals(host)||"m.youtube.com".equals(host)))return null;
  String path=u.getPath(),id=null;
  if("youtu.be".equals(host)&&path.matches("/[A-Za-z0-9_-]{11}"))id=path.substring(1);
  else if(path.matches("/(shorts|embed)/[A-Za-z0-9_-]{11}"))id=path.substring(path.lastIndexOf('/')+1);
  else if("/watch".equals(path)&&u.getRawQuery()!=null)for(String pair:u.getRawQuery().split("&")){String[] kv=pair.split("=",2);if(kv.length==2&&"v".equals(kv[0])){id=URLDecoder.decode(kv[1],"UTF-8");break;}}
  return id!=null&&id.matches("[A-Za-z0-9_-]{11}")?id:null;
 }catch(Exception e){return null;}}
}
