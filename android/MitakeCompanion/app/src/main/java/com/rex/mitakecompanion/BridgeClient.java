package com.rex.mitakecompanion;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
public final class BridgeClient {
    public JSONObject fetch(Pairing pair,long after,String session)throws Exception {
        String query="/v1/snapshot?after="+Math.max(0,after)+"&session="+java.net.URLEncoder.encode(session,"UTF-8");
        HttpURLConnection c=(HttpURLConnection)new URL(pair.address+query).openConnection();
        c.setConnectTimeout(4000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization","Bearer "+pair.token);
        try {
            int code=c.getResponseCode();if(code==401)throw new java.io.IOException("配對已失效，請重新掃碼");
            if(code!=200)throw new java.io.IOException("連線回應 "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>2097152)throw new java.io.IOException("資料超出上限");out.write(bytes,0,n);}
                JSONObject result=new JSONObject(out.toString("UTF-8"));if(result.optInt("protocol")!=1)throw new java.io.IOException("版本不相容，請更新電腦端與手機版");return result;
            }
        }finally{c.disconnect();}
    }
}
