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
    JSONObject release(String version,String url)throws Exception{
        return new JSONObject().put("tag_name","mitake-companion-v"+version).put("assets",new JSONArray().put(new JSONObject().put("name","MitakeCompanion-v"+version+".apk").put("browser_download_url",url).put("size",2048).put("digest","sha256:"+"a".repeat(64))));
    }
    @Test public void updatesChooseOnlyNewerPublishedCompanionApks()throws Exception{
        String root=UpdatePolicy.ROOT;
        JSONObject newer=release("0.1.3",root+"mitake-companion-v0.1.3/MitakeCompanion-v0.1.3.apk");
        JSONArray rows=new JSONArray().put(release("0.1.4","https://example.com/other.apk")).put(new JSONObject().put("tag_name","mitake-latest")).put(newer);
        assertEquals("0.1.3",UpdatePolicy.newest(rows,"0.1.2").version);
        newer.put("draft",true);assertNull(UpdatePolicy.newest(rows,"0.1.2"));
        newer.put("draft",false).put("prerelease",true);assertNull(UpdatePolicy.newest(rows,"0.1.2"));
        newer.put("prerelease",false);assertNull(UpdatePolicy.newest(rows,"0.1.3"));
        assertTrue(UpdatePolicy.compare("0.1.10","0.1.9")>0);
    }
    @Test public void updaterRejectsWrongChecksumAndOversizedAssets()throws Exception{
        assertEquals("a".repeat(64),UpdatePolicy.checksum("a".repeat(64)+"  app.apk\n","app.apk"));
        assertThrows(IllegalArgumentException.class,()->UpdatePolicy.checksum("a".repeat(64)+"  wrong.apk","app.apk"));
        assertThrows(IllegalArgumentException.class,()->UpdatePolicy.checksum("broken  app.apk","app.apk"));
        JSONObject r=release("0.1.3",UpdatePolicy.ROOT+"mitake-companion-v0.1.3/MitakeCompanion-v0.1.3.apk");
        r.getJSONArray("assets").getJSONObject(0).put("size",UpdatePolicy.MAX_APK+1);
        assertNull(UpdatePolicy.newest(new JSONArray().put(r),"0.1.2"));
    }
    @Test public void voiceUsesObservationWordingAndPersistentSwitch()throws Exception{
        JSONObject e=event(5,100,false);
        assertTrue(SpeechAnnouncer.phrase(e).contains("台積電"));assertTrue(SpeechAnnouncer.phrase(e).contains("股號"));
        assertTrue(SpeechAnnouncer.phrase(e).contains("必要檢核待確認"));
        assertFalse(SpeechAnnouncer.phrase(e).contains("買進"));
        assertTrue(SpeechAnnouncer.phrase(event(6,100,true)).contains("必要檢核已通過"));
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();a.showVoice();
        AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        android.widget.Switch toggle=findSwitch(dialog.getWindow().getDecorView());assertNotNull(toggle);assertTrue(toggle.isChecked());toggle.setChecked(false);
        assertFalse(BridgeService.prefs(a).getBoolean("voice_enabled",true));a.finish();
    }
    android.widget.Switch findSwitch(View v){if(v instanceof android.widget.Switch)return (android.widget.Switch)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){android.widget.Switch found=findSwitch(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    @Test public void connectionDiagnosisDistinguishesFailuresWithoutLeakingToken(){
        assertTrue(ConnectionDiagnosis.reason(new java.net.SocketTimeoutException("token-secret")).contains("逾時"));
        assertTrue(ConnectionDiagnosis.reason(new java.net.ConnectException()).contains("無法連到電腦"));
        assertTrue(ConnectionDiagnosis.reason(new java.io.IOException("配對已失效")).contains("重新掃碼"));
        assertFalse(ConnectionDiagnosis.reason(new java.io.IOException("token-secret")).contains("token-secret"));
    }
    String candleFixture()throws Exception{android.os.SystemClock.setCurrentTimeMillis(1792483200000L);try(InputStream in=getClass().getResourceAsStream("/yahoo_2330_5m.json")){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    @Test public void fiveMinuteParserUsesActualOhlcSchemaAndRejectsMismatches()throws Exception{
        String fixture=candleFixture();IntradayData d=IntradayData.parse(fixture,"2330.TW");
        assertEquals(4,d.bars.size());assertEquals(2400,d.bars.get(0).open,.01);assertTrue(d.bars.get(1).time>d.bars.get(0).time);
        assertThrows(IllegalArgumentException.class,()->IntradayData.parse(fixture,"2330.TWO"));
        assertThrows(IllegalArgumentException.class,()->IntradayData.parse(fixture.replace("\"5m\"","\"1d\""),"2330.TW"));
        assertTrue(IntradayData.validCode("0050"));assertFalse(IntradayData.validCode("2330.TW"));assertFalse(IntradayData.validCode("../2330"));
    }
    @Test public void nullAndInvalidCandlesAreSkippedWithoutInventingBars()throws Exception{
        JSONObject json=new JSONObject(candleFixture()),r=json.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject q=r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        q.getJSONArray("open").put(0,JSONObject.NULL);q.getJSONArray("high").put(1,1);
        IntradayData d=IntradayData.parse(json.toString(),"2330.TW");assertEquals(2,d.bars.size());
        q.getJSONArray("close").put(2,JSONObject.NULL).put(3,JSONObject.NULL);
        assertThrows(IllegalArgumentException.class,()->IntradayData.parse(json.toString(),"2330.TW"));
    }
    @Test public void nativeCandleChartDrawsAndStockCardIsReused()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        IntradayRepository repo=new IntradayRepository();MiniCandleView chart=new MiniCandleView(a,"2330",repo);chart.setTestData(IntradayData.parse(candleFixture(),"2330.TW"));
        chart.layout(0,0,900,560);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(900,560,android.graphics.Bitmap.Config.ARGB_8888);chart.draw(new android.graphics.Canvas(bitmap));
        assertTrue(chart.getContentDescription().toString().contains("五分K"));assertTrue(chart.getContentDescription().toString().contains("來源可能延遲"));
        a.cards.removeAllViews();JSONObject r=event(5,100,false).put("peak",5);a.addCard(r,false);View first=a.cards.getChildAt(0);a.cards.removeAllViews();a.addCard(r,false);assertSame(first,a.cards.getChildAt(0));
        assertTrue(first instanceof StockCard);repo.close();a.finish();
    }
}
