package com.rex.mitakecompanion;
import android.content.Context;
import android.net.*;
import java.net.*;
public final class ConnectionDiagnosis {
    static String network(Context c){try{
        ConnectivityManager m=c.getSystemService(ConnectivityManager.class);NetworkCapabilities n=m.getNetworkCapabilities(m.getActiveNetwork());
        if(n==null)return "沒有可用網路，請開啟 Wi-Fi 或私人 VPN";
        if(n.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "私人 VPN 已連接，請確認它可通往電腦";
        if(n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Wi-Fi：請與電腦連同一個區域網路，避免訪客網路隔離";
        if(n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "行動網路：電腦的區域網路 IP 無法經一般 4G／5G 直接連接；請改同 Wi-Fi 或先連私人 VPN";
        return "請確認手機網路可連到電腦的私人 IP";
    }catch(RuntimeException e){return "請確認手機與電腦連同 Wi-Fi 或私人 VPN";}}
    static String reason(Throwable error){
        String m=error.getMessage()==null?"":error.getMessage();
        if(m.contains("配對"))return "配對失效或格式錯誤，請在電腦顯示新配對碼並重新掃碼";
        if(m.contains("版本不相容"))return "版本不相容，請更新電腦版與手機版";
        if(m.contains("403"))return "電腦拒絕此網路來源，請使用同 Wi-Fi 或私人 VPN";
        if(error instanceof SocketTimeoutException)return "連線逾時：請檢查電腦位址、Windows 防火牆與網路隔離";
        if(error instanceof ConnectException||error instanceof NoRouteToHostException)return "無法連到電腦：請啟用手機連動服務，確認電腦 IP 與私人網路防火牆";
        if(error instanceof UnknownHostException)return "電腦位址無法解析，請重新掃描電腦配對碼";
        return "無法取得電腦資料，請用連線診斷檢查網路與配對；錯誤類型："+error.getClass().getSimpleName();
    }
}
