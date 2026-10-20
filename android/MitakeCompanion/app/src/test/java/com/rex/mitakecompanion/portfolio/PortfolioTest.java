package com.rex.mitakecompanion.portfolio;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PortfolioTest {
    @Before public void reset()throws Exception{
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("portfolio_secret",0).edit().clear().commit();
        PortfolioStore s=PortfolioStore.get(RuntimeEnvironment.getApplication());File db=new File(s.root(),"portfolio.json");Files.deleteIfExists(db.toPath());
    }
    @Test public void quantitiesAreSharesAndProfitIsDecimalExact()throws Exception{
        JSONObject h=PortfolioStore.holding("2330","台積電","1000","100.10","110.20");assertTrue(PortfolioStore.pnl(h).startsWith("10100 元"));
        assertThrows(IllegalArgumentException.class,()->PortfolioStore.holding("2330","台積電","1.5","100","110"));
        assertThrows(IllegalArgumentException.class,()->PortfolioStore.holding("2330","台積電","0","100","110"));
        assertThrows(IllegalArgumentException.class,()->PortfolioStore.number("NaN",false,true));
        assertThrows(IllegalArgumentException.class,()->PortfolioStore.number("1e2147483647",false,true));
        assertThrows(IllegalArgumentException.class,()->PortfolioStore.number("1,2",true,false));
        assertEquals("1200",PortfolioStore.number("1,200",true,false));
        assertTrue(PortfolioStore.pnl(PortfolioStore.holding("2330","台積電","1000","","")).contains("待核對"));
    }
    @Test public void extractedRowsStayDraftAndUnknownDoesNotBecomeZero()throws Exception{
        JSONArray a=PortfolioStore.parseExtract("{\"holdings\":[{\"code\":\"2330\",\"name\":\"台積電\",\"shares\":null,\"cost\":null,\"price\":\"100\"}]}");
        assertEquals("",a.getJSONObject(0).getString("shares"));assertEquals("",a.getJSONObject(0).getString("cost"));
        assertEquals(0,PortfolioStore.get(RuntimeEnvironment.getApplication()).read().getJSONArray("holdings").length());
        assertThrows(IllegalArgumentException.class,()->PortfolioStore.parseExtract("{\"holdings\":[{\"code\":\"2330\"},{\"code\":\"2330\"}]}"));
        assertThrows(IllegalArgumentException.class,()->PortfolioStore.parseExtract("{\"holdings\":[{\"code\":\"../bad\"}]}"));
    }
    @Test public void snapshotsSurviveHoldingEditsAndImagesRemainPerRun()throws Exception{
        PortfolioStore s=PortfolioStore.get(RuntimeEnvironment.getApplication());JSONObject h=PortfolioStore.holding("2330","台積電","1000","100","110");s.saveHolding(h);
        JSONObject first=s.newRun(h),second=s.newRun(h);h.put("shares","2000");s.saveHolding(h);
        assertEquals("1000",s.run(first.getString("id")).getJSONObject("holding").getString("shares"));
        File image=s.imageFile(first.getString("id"),"K線");Files.write(image.toPath(),new byte[]{1,2,3});s.attach(first.getString("id"),"K線",image,"manual","2330");
        assertEquals(0,s.run(second.getString("id")).getJSONObject("pages").length());
        assertEquals(1,s.run(first.getString("id")).getJSONObject("pages").length());
        s.deleteRun(first.getString("id"));assertFalse(image.exists());assertEquals(1,s.read().getJSONArray("runs").length());
        assertThrows(IllegalArgumentException.class,()->s.imageFile("../../outside","K線"));
    }
    @Test public void changedImageInvalidatesOldAnalysis()throws Exception{
        PortfolioStore s=PortfolioStore.get(RuntimeEnvironment.getApplication());JSONObject r=s.newRun(PortfolioStore.holding("2330","台積電","1000","100","110"));
        r.put("analysis","old answer").put("analyzed",123);s.updateRun(r);File f=s.imageFile(r.getString("id"),"法人");Files.write(f.toPath(),new byte[]{1});s.attach(r.getString("id"),"法人",f,"manual","2330");
        assertFalse(s.run(r.getString("id")).has("analysis"));assertFalse(s.run(r.getString("id")).has("analyzed"));
    }
    @Test public void imageRequestPreservesLabelsAndDoesNotIncludeKey()throws Exception{
        File file=File.createTempFile("test-image",".jpg");Files.write(file.toPath(),new byte[]{1,2,3});
        JSONObject r=GeminiClient.request("review",Collections.singletonList(file),Collections.singletonList("法人"),false);
        JSONArray parts=r.getJSONArray("contents").getJSONObject(0).getJSONArray("parts");assertTrue(parts.getJSONObject(1).getString("text").contains("法人"));
        assertEquals("image/jpeg",parts.getJSONObject(2).getJSONObject("inlineData").getString("mimeType"));assertFalse(r.toString().contains("api-key"));
        assertThrows(IllegalArgumentException.class,()->GeminiClient.request("bad",Collections.singletonList(file),Collections.emptyList(),false));file.delete();
    }
    @Test public void truncatedOrBlockedApiResultsCannotBecomeAnalysis()throws Exception{
        assertThrows(IOException.class,()->GeminiClient.response(new JSONObject("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}")));
        assertThrows(IOException.class,()->GeminiClient.response(new JSONObject("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")));
        assertEquals("result",GeminiClient.response(new JSONObject("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"hidden\",\"thought\":true},{\"text\":\"result\"}]}}]}")));
    }
    @Test public void captureDoesNotConfuseSimilarStocksOrBroadTabMatches(){
        assertTrue(ChipCaptureService.exactCode("台積電 2330 即時","2330"));assertFalse(ChipCaptureService.exactCode("12330","2330"));assertFalse(ChipCaptureService.exactCode("2330A","2330"));
        assertTrue(ChipCaptureService.pageLabel("K 線","K線"));assertFalse(ChipCaptureService.pageLabel("K線設定","K線"));
        assertFalse(ChipCaptureService.running());assertFalse(ChipCaptureService.available());
    }
    @Test public void portfolioActivityStartsWithoutKeyOrExternalApp(){
        PortfolioActivity a=Robolectric.buildActivity(PortfolioActivity.class).setup().get();assertFalse(a.isFinishing());
        assertFalse(new GeminiClient(a).hasKey());a.settings();assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing());a.finish();
    }
}
