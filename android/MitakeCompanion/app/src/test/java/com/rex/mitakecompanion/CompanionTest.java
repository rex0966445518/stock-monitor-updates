package com.rex.mitakecompanion;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class CompanionTest {
    static final String TOKEN="a".repeat(48);
    JSONObject event(int matched,double ts,boolean hit)throws Exception{return new JSONObject().put("code","2330").put("name","台積電").put("matched",matched).put("total",6).put("ts",ts).put("price",100).put("gain",3).put("valid",true).put("confirmed",hit).put("seq",1);}
    @Before public void clear(){BridgeService.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();BridgeService.latest=null;BridgeService.receivedAt=0;BridgeService.connection="尚未連線";}
    @Test public void pairingValidatesPrivateHostsAndToken(){
        Pairing p=Pairing.parse("mitake://pair?address=http%3A%2F%2F192.168.1.20%3A8765&token="+TOKEN);
        assertEquals("http://192.168.1.20:8765",p.address);
        assertTrue(Pairing.privateHost("100.100.20.10"));assertTrue(Pairing.privateHost("172.31.2.2"));
        for(String host:new String[]{"8.8.8.8","172.32.0.1","192.168.1.999","example.com"})assertFalse(Pairing.privateHost(host));
        assertThrows(IllegalArgumentException.class,()->new Pairing("http://192.168.1.2/path",TOKEN));
        assertThrows(IllegalArgumentException.class,()->new Pairing("http://192.168.1.2","bad"));
    }
    @Test public void onlyFreshValidSixRuleCandidatesNotify()throws Exception{
        assertTrue(AlertPolicy.eligible(event(5,100,false),110,true));assertTrue(AlertPolicy.eligible(event(6,100,true),110,true));
        assertFalse(AlertPolicy.eligible(event(4,100,false),110,true));assertFalse(AlertPolicy.eligible(event(5,100,false),116,true));
        assertFalse(AlertPolicy.eligible(event(6,100,true),110,false));assertFalse(AlertPolicy.eligible(event(6,100,true).put("valid",false),110,true));
        assertFalse(AlertPolicy.eligible(event(6,100,true).put("total",5),110,true));
        assertTrue(AlertPolicy.detail(event(6,100,false)).contains("待確認"));assertTrue(AlertPolicy.detail(event(6,100,true)).contains("已通過"));
    }
    @Test public void actualAuthenticatedHttpClientAndCursor()throws Exception{
        ServerSocket server=new ServerSocket(0);server.setSoTimeout(5000);
        final String[] seen={""};
        Thread worker=new Thread(()->{try(Socket client=server.accept()){
            BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
            String line;while((line=reader.readLine())!=null&&!line.isEmpty())if(line.startsWith("Authorization: "))seen[0]=line.substring(15);
            byte[] bytes="{\"protocol\":1,\"seq\":42}".getBytes(StandardCharsets.UTF_8);
            OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
        }catch(Exception e){seen[0]=e.toString();}});worker.start();
        try{JSONObject value=new BridgeClient().fetch(new Pairing("http://127.0.0.1:"+server.getLocalPort(),TOKEN),41,"session");assertEquals(42,value.getInt("seq"));assertEquals("Bearer "+TOKEN,seen[0]);}finally{server.close();worker.join(6000);}
    }
    @Test public void realActivityShowsOfflineAndDoesNotFabricateStocks(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("0",a.counts6.getText().toString());assertEquals("0",a.counts5.getText().toString());assertEquals(1,a.cards.getChildCount());
        assertTrue(a.connection.getText().toString().contains("停止"));a.showPairing();
        assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        a.finish();
    }
    @Test public void activityRendersActualCandidatesAndRetainsWeak()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        BridgeService.prefs(a).edit().putBoolean("enabled",true).apply();
        BridgeService.receivedAt=android.os.SystemClock.elapsedRealtime()+1;
        BridgeService.latest=new JSONObject().put("server_time",110).put("active",true).put("status","第2輪｜漲幅排行").put("rows",new JSONArray().put(event(6,100,true).put("peak",6)).put(event(4,105,false).put("peak",5).put("code","2317")));
        a.refresh();assertEquals("1",a.counts6.getText().toString());assertEquals(2,a.cards.getChildCount());
        BridgeService.receivedAt=0;a.refresh();assertEquals("0",a.counts6.getText().toString());assertEquals(2,a.cards.getChildCount());a.finish();
    }
    @Test public void scanRequestsPermissionBeforeLaunchingCamera(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        org.robolectric.Shadows.shadowOf(a.getApplication()).denyPermissions(android.Manifest.permission.CAMERA);
        a.scan();
        assertEquals(MainActivity.CAMERA_REQUEST,org.robolectric.Shadows.shadowOf(a).getLastRequestedPermission().requestCode);
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",org.robolectric.Shadows.shadowOf(a).getNextStartedActivityForResult().intent.getAction());
        a.onRequestPermissionsResult(MainActivity.CAMERA_REQUEST,new String[]{android.Manifest.permission.CAMERA},new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing());
        assertFalse(a.isFinishing());a.finish();
    }
    @Test public void scanWithPermissionLaunchesEmbeddedCapture(){
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        org.robolectric.Shadows.shadowOf(a.getApplication()).grantPermissions(android.Manifest.permission.CAMERA);
        a.scan();
        android.content.Intent intent=org.robolectric.Shadows.shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals("com.journeyapps.barcodescanner.CaptureActivity",intent.getComponent().getClassName());
        assertFalse(intent.getBooleanExtra("BEEP_ENABLED",true));
        a.finish();
    }
    @Test public void captureActivityInflatesWithPackagedDependencies(){
        org.robolectric.android.controller.ActivityController<com.journeyapps.barcodescanner.CaptureActivity> controller=
            Robolectric.buildActivity(com.journeyapps.barcodescanner.CaptureActivity.class,new com.google.zxing.integration.android.IntentIntegrator(Robolectric.buildActivity(MainActivity.class).setup().get()).createScanIntent());
        controller.create().start();
        assertNotNull(controller.get().findViewById(com.google.zxing.client.android.R.id.zxing_barcode_scanner));
        controller.stop().destroy();
    }
}
