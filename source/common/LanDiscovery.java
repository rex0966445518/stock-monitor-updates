package tw.rex.link;
import java.net.*;import java.util.*;
public final class LanDiscovery {
 public static final int PORT=45322;
 public static Set<InetAddress> broadcasts() throws Exception {
  Set<InetAddress> out=new HashSet<InetAddress>();out.add(InetAddress.getByName("255.255.255.255"));
  Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface n=all.nextElement();if(!n.isUp()||n.isLoopback())continue;for(InterfaceAddress a:n.getInterfaceAddresses())if(a.getBroadcast()!=null)out.add(a.getBroadcast());}return out;
 }
 public static boolean privateV4(InetAddress a){return a instanceof Inet4Address&&(a.isSiteLocalAddress()||a.isLinkLocalAddress())&&!a.isLoopbackAddress();}
}
