package com.rex.mitakecompanion;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
public final class Pairing {
    public final String address,token;
    public Pairing(String address,String token) {
        address=address.trim().replaceAll("/+$","");token=token.trim();
        URI uri=URI.create(address);
        if (!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||!privateHost(uri.getHost())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath()==null||uri.getPath().isEmpty())||uri.getPort()>65535||uri.getPort()==0)throw new IllegalArgumentException("請使用電腦顯示的私人網路 IP 與連接埠");
        if(!token.matches("[a-f0-9]{48}"))throw new IllegalArgumentException("配對碼格式不正確，請重新掃碼");
        this.address=address;this.token=token;
    }
    public static boolean privateHost(String host) {
        if(host==null||!host.matches("\\d{1,3}(\\.\\d{1,3}){3}"))return false;
        String[] parts=host.split("\\.");int[] n=new int[4];for(int i=0;i<4;i++){n[i]=Integer.parseInt(parts[i]);if(n[i]>255)return false;}
        return n[0]==10||n[0]==127||n[0]==192&&n[1]==168||n[0]==172&&n[1]>=16&&n[1]<=31||n[0]==100&&n[1]>=64&&n[1]<=127;
    }
    public static Pairing parse(String text) {
        URI uri=URI.create(text.trim());
        if(!"mitake".equals(uri.getScheme())||!"pair".equals(uri.getHost()))throw new IllegalArgumentException("這不是漲停雷達配對碼");
        HashMap<String,String> q=new HashMap<>();for(String p:uri.getRawQuery().split("&")){String[] kv=p.split("=",2);if(kv.length==2)q.put(kv[0],android.net.Uri.decode(kv[1]));}
        return new Pairing(q.getOrDefault("address",""),q.getOrDefault("token",""));
    }
}
