package tw.rex.link;
import java.security.*;import java.security.spec.*;import javax.crypto.*;import javax.crypto.spec.*;import android.util.Base64;import org.json.*;
public final class Crypto {
 public static byte[] random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return b;}
 public static String b64(byte[] b){return Base64.encodeToString(b,Base64.NO_WRAP);}
 public static byte[] bytes(String s){return Base64.decode(s,Base64.NO_WRAP);}
 public static String token(){return b64(random(32));}
 public static String digest(String s)throws Exception{return b64(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));}
 public static String seal(String secret,JSONObject obj)throws Exception{byte[] iv=random(12);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(bytes(secret),"AES"),new GCMParameterSpec(128,iv));return b64(iv)+"."+b64(c.doFinal(obj.toString().getBytes("UTF-8")));}
 public static JSONObject open(String secret,String data)throws Exception{String[] parts=data.split("\\.");if(parts.length!=2)throw new Exception("invalid frame");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(bytes(secret),"AES"),new GCMParameterSpec(128,bytes(parts[0])));return new JSONObject(new String(c.doFinal(bytes(parts[1])),"UTF-8"));}
 public static String encrypt(String pub,JSONObject data)throws Exception{Cipher c=Cipher.getInstance("RSA/ECB/PKCS1Padding");c.init(Cipher.ENCRYPT_MODE,KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes(pub))));return b64(c.doFinal(data.toString().getBytes("UTF-8")));}
 public static JSONObject decrypt(PrivateKey key,String data)throws Exception{Cipher c=Cipher.getInstance("RSA/ECB/PKCS1Padding");c.init(Cipher.DECRYPT_MODE,key);return new JSONObject(new String(c.doFinal(bytes(data)),"UTF-8"));}
 public static String pinHash(String pin,String salt)throws Exception{javax.crypto.SecretKeyFactory f=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");return b64(f.generateSecret(new PBEKeySpec(pin.toCharArray(),bytes(salt),100000,256)).getEncoded());}
}
