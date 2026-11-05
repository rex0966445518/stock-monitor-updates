import tw.rex.link.YouTubeLink;
public class YouTubeLinkTest {
 public static void main(String[] args){String id="M7lc1UVf-VE";
 String[] good={"https://www.youtube.com/watch?v="+id,"https://m.youtube.com/watch?feature=share&v="+id,"https://youtu.be/"+id+"?si=abcd","https://youtube.com/shorts/"+id,"https://www.youtube.com/embed/"+id};
 String[] bad={"https://evil.example/watch?v="+id,"https://youtube.com.evil.example/watch?v="+id,"http://www.youtube.com/watch?v="+id,"javascript:alert(1)","intent://youtube/watch?v="+id,"https://youtube.com/results?search_query=x","https://www.youtube.com/watch?v=bad","https://user@youtube.com/watch?v="+id,"https://youtube.com:8080/watch?v="+id,"https://youtu.be/"+id+"/more","https://www.youtube.com/foo?v="+id,"https://youtube.com/watch?v=%27<script>",null};
 for(String s:good)if(!id.equals(YouTubeLink.videoId(s)))throw new AssertionError(s);
 for(String s:bad)if(YouTubeLink.videoId(s)!=null)throw new AssertionError(s);
 System.out.println((good.length+bad.length)+" YouTube URL checks passed");}
}
