package tw.rex.link;
import java.io.*;import java.net.*;import org.json.*;
public final class Wire {
 public static JSONObject read(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int n=0;n<131072;n++){int c=in.read();if(c==-1)throw new IOException("closed");if(c==10)return new JSONObject(b.toString("UTF-8"));b.write(c);}throw new IOException("oversized");}
 public static void write(OutputStream out,JSONObject value)throws Exception{out.write((value.toString()+"\n").getBytes("UTF-8"));out.flush();}
 public static JSONObject call(String host,int port,JSONObject body)throws Exception{try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),2500);s.setSoTimeout(4000);write(s.getOutputStream(),body);return read(s.getInputStream());}}
}
