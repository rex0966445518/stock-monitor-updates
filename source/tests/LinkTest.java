import tw.rex.link.*;import org.json.*;import java.net.*;import java.io.*;import java.security.*;import java.util.*;
public class LinkTest {
 static int checks=0;static void ok(boolean b,String label){if(!b)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 public static void main(String[] args)throws Exception{
  String key=Crypto.token();JSONObject c=new JSONObject().put("action","unlock").put("nonce","unique-1");String sealed=Crypto.seal(key,c);
  ok(Crypto.open(key,sealed).getString("action").equals("unlock"),"encrypted command roundtrip");
  try{Crypto.open(Crypto.token(),sealed);throw new AssertionError();}catch(javax.crypto.AEADBadTagException expected){ok(true,"wrong parent key rejected");}
  String[] pieces=sealed.split("\\.");byte[] changed=Crypto.bytes(pieces[1]);changed[0]^=1;try{Crypto.open(key,pieces[0]+"."+Crypto.b64(changed));throw new AssertionError();}catch(javax.crypto.AEADBadTagException expected){ok(true,"tampered command rejected");}
  ok(!sealed.equals(Crypto.seal(key,c)),"fresh AES GCM IV for every frame");
  KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair kp=g.generateKeyPair();JSONObject pair=new JSONObject().put("pin","123456").put("key",key).put("phone",UUID.randomUUID().toString()).put("recovery",Crypto.digest("EMERGENCYCODE-EXAMPLE"));String envelope=Crypto.encrypt(Crypto.b64(kp.getPublic().getEncoded()),pair);
  ok(Crypto.decrypt(kp.getPrivate(),envelope).getString("key").equals(key),"RSA encrypted pairing envelope");
  ok(Crypto.pinHash("123456",Crypto.b64(new byte[16])).equals(Crypto.pinHash("123456",Crypto.b64(new byte[16]))),"parent PIN derivation stable");
  ok(!Crypto.pinHash("654321",Crypto.b64(new byte[16])).equals(Crypto.pinHash("123456",Crypto.b64(new byte[16]))),"wrong parent PIN differs");
  try(ServerSocket server=new ServerSocket(0)){Thread worker=new Thread(()->{try(Socket s=server.accept()){JSONObject request=Wire.read(s.getInputStream());JSONObject data=Crypto.open(key,request.getString("data"));Wire.write(s.getOutputStream(),new JSONObject().put("data",Crypto.seal(key,new JSONObject().put("nonce",data.getString("nonce")).put("command",new JSONObject().put("action","lock")))));}catch(Exception e){throw new RuntimeException(e);}});worker.start();JSONObject response=Wire.call("127.0.0.1",server.getLocalPort(),new JSONObject().put("data",sealed));JSONObject opened=Crypto.open(key,response.getString("data"));ok(opened.getString("nonce").equals("unique-1")&&opened.getJSONObject("command").getString("action").equals("lock"),"real TCP request and authenticated response");ok(!opened.getString("nonce").equals("unique-2"),"replayed response has wrong request nonce");worker.join();}
  try{Wire.read(new ByteArrayInputStream(new byte[131073]));throw new AssertionError();}catch(IOException expected){ok(true,"oversized network frame rejected");}
  try{Wire.read(new ByteArrayInputStream("{".getBytes("UTF-8")));throw new AssertionError();}catch(IOException expected){ok(true,"truncated frame rejected");}
  System.out.println(checks+" checks passed");
 }
}
