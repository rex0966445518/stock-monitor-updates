package tw.rex.parentcontrol;
import tw.rex.link.*;import org.json.*;import java.security.*;
public class AutoPairTest {
 static int n;static void check(boolean b){if(!b)throw new AssertionError("case "+n);n++;}
 public static void main(String[] args)throws Exception{
 ParentService.devices.clear();ParentService s=new ParentService();s.p=MultiParentTest.prefs();s.phoneId="12345678-1234-1234-1234-123456789012";
 String invitation=Crypto.token();
 KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair keys=gen.generateKeyPair();String pub=Crypto.b64(keys.getPublic().getEncoded());
 JSONObject hello=new JSONObject().put("id","tv").put("type","hello").put("pub",pub).put("invitePhone","other").put("invite",invitation);
 check(s.handle(hello).getInt("pairVersion")==2);check(ParentService.devices.get("tv").invite.isEmpty());
 hello.put("invitePhone",s.phoneId);s.handle(hello);s.acceptInvite("tv","recovery");
 JSONObject envelope=Crypto.decrypt(keys.getPrivate(),s.handle(hello).getString("pair"));
 check(envelope.getString("invite").equals(invitation));check(envelope.getString("phone").equals(s.phoneId));
 check(s.p.getString("secret.tv","").isEmpty());
 s.acceptInvite("tv","recovery");check(Crypto.decrypt(keys.getPrivate(),s.handle(hello).getString("pair")).getString("key").equals(envelope.getString("key")));
 String key=envelope.getString("key");s.handle(new JSONObject().put("id","tv").put("type","poll").put("data",Crypto.seal(key,new JSONObject().put("nonce","one").put("status",new JSONObject()))));
 check(ParentService.devices.get("tv").paired);check(s.p.getString("secret.tv","").equals(key));
 ParentService.devices.get("tv").inviteUntil=0;try{s.acceptInvite("tv","recovery");throw new AssertionError();}catch(Exception expected){n++;}
 s.handle(hello.put("invitePhone",""));check(ParentService.devices.get("tv").invite.isEmpty());
 s.handle(hello.put("invitePhone",s.phoneId).put("invite",Crypto.token()));s.acceptInvite("tv","recovery");s.handle(hello.put("invitePhone",""));check(ParentService.devices.get("tv").pending.isEmpty());check(ParentService.devices.get("tv").pendingSecret.isEmpty());
 s.pool.shutdownNow();System.out.println(n+" parent invitation handshake checks passed");
 }
}
