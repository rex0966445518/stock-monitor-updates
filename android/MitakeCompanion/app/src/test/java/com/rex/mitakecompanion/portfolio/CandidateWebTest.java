package com.rex.mitakecompanion.portfolio;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class CandidateWebTest {
    PortfolioStore store;
    @Before public void reset()throws Exception{store=PortfolioStore.get(RuntimeEnvironment.getApplication());Files.deleteIfExists(new File(store.root(),"portfolio.json").toPath());RuntimeEnvironment.getApplication().getSharedPreferences("portfolio_settings",0).edit().clear().commit();RuntimeEnvironment.getApplication().getSharedPreferences("portfolio_secret",0).edit().clear().commit();}
    JSONObject row(int n)throws Exception{return new JSONObject().put("code","2330").put("name","台積電").put("matched",n).put("total",6).put("ts",100).put("price",100);}
    JSONObject complete()throws Exception{JSONObject r=CandidateCapture.newRun(store,row(5));for(String page:PortfolioStore.PAGES){File f=store.imageFile(r.getString("id"),page);Files.write(f.toPath(),("jpeg-"+page).getBytes(java.nio.charset.StandardCharsets.UTF_8));store.attach(r.getString("id"),page,f,"test fixture","2330");}return store.run(r.getString("id"));}
    @Test public void candidateIsNotInventedHoldingAndNeverAutoSendsGemini()throws Exception{
        JSONObject r=CandidateCapture.newRun(store,row(6));assertTrue(r.getBoolean("auto_web"));assertFalse(r.getBoolean("auto_analyze"));assertEquals("",r.getJSONObject("holding").getString("shares"));assertEquals("",r.getJSONObject("holding").getString("cost"));assertEquals(0,store.read().getJSONArray("holdings").length());
        assertThrows(IllegalArgumentException.class,()->CandidateCapture.newRun(store,row(4)));assertThrows(IllegalArgumentException.class,()->CandidateCapture.newRun(store,row(6).put("demo",true)));
    }
    @Test public void completeBundlePreservesSixLabelsAndManifest()throws Exception{
        JSONObject r=complete();File bundle=AnalysisBundle.create(store,r.getString("id"));try(ZipFile zip=new ZipFile(bundle)){
            assertEquals(7,zip.size());for(int i=0;i<6;i++)assertNotNull(zip.getEntry((i+1)+"-"+PortfolioStore.PAGES[i]+".jpg"));
            JSONObject manifest=new JSONObject(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));assertEquals("2330",manifest.getJSONObject("target").getString("code"));assertEquals(6,manifest.getJSONArray("pages").length());assertEquals(64,manifest.getJSONArray("pages").getJSONObject(0).getString("sha256").length());
        }assertTrue(store.run(r.getString("id")).has("bundle"));
    }
    @Test public void incompleteOrForeignImagesNeverBecomeCompleteBundle()throws Exception{
        JSONObject r=CandidateCapture.newRun(store,row(5));assertThrows(IllegalStateException.class,()->AnalysisBundle.create(store,r.getString("id")));
        JSONObject full=complete();String id=full.getString("id");full.getJSONObject("pages").getJSONObject("法人").put("path","/tmp/foreign.jpg");store.updateRun(full);assertThrows(IOException.class,()->AnalysisBundle.create(store,id));assertFalse(store.run(id).has("bundle"));
    }
    @Test public void replacingImageInvalidatesExportBundle()throws Exception{
        JSONObject r=complete();String id=r.getString("id");File bundle=AnalysisBundle.create(store,id);File f=store.imageFile(id,"K線");Files.write(f.toPath(),new byte[]{1});store.attach(id,"K線",f,"replacement","2330");assertFalse(bundle.exists());assertFalse(store.run(id).has("bundle"));
    }
    @Test public void searchUrlContainsOnlyPublicCandidateTerms()throws Exception{
        String url=GoogleSearchCapture.url("2330","台積電");assertTrue(url.startsWith("https://www.google.com/search?"));String q=android.net.Uri.parse(url).getQueryParameter("q");assertTrue(q.contains("2330"));assertTrue(q.contains("籌碼日報"));assertFalse(q.contains("成本"));assertFalse(q.contains("key"));assertThrows(IllegalArgumentException.class,()->GoogleSearchCapture.url("../bad","name"));
    }
    @Test public void onlyExactGoogleOriginsAndQueriesQualify(){
        assertTrue(GoogleSearchCapture.googleAddress("www.google.com/search?q=2330"));assertTrue(GoogleSearchCapture.googleAddress("https://www.google.com.tw/search?q=2330"));
        for(String bad:new String[]{"https://google.com.evil.test/search","https://evil.test/google.com","https://google.com@evil.test/","http://google.com/","https://google.com:444/"})assertFalse(bad,GoogleSearchCapture.googleAddress(bad));
        assertFalse(GoogleSearchCapture.searchPage("google.com",Collections.singletonList("2330 台積電")));assertTrue(GoogleSearchCapture.searchPage("google.com/search?q=2330",Collections.emptyList()));assertTrue(GoogleSearchCapture.searchPage("google.com",Collections.singletonList("搜尋結果")));
        assertTrue(GoogleSearchCapture.sameQuery("2330 台積電", "2330  台積電"));assertFalse(GoogleSearchCapture.sameQuery("2330 台積電","2317 鴻海"));
    }
    @Test public void excerptsAreBoundedAndNeverFabricateAConclusion(){
        String text="2330台積電公開搜尋摘要，應以原始公告及網頁資料日期核對。";
        List<String> got=GoogleSearchCapture.excerpts(Arrays.asList("Google","全部",text,text,"2330 台積電","Google 帳戶：使用者及私人信箱 user@example.com","您的投資組合包含私人持股以及成本，不是公開搜尋摘要"),"2330 台積電");assertEquals(Collections.singletonList(text),got);
        List<String> many=new ArrayList<>();for(int i=0;i<100;i++)many.add(i+"資料"+"x".repeat(2000));List<String> bounded=GoogleSearchCapture.excerpts(many,"");assertTrue(bounded.stream().mapToInt(String::length).sum()<=12000);assertTrue(bounded.size()<=12);
    }
    @Test public void firstUseWithoutPermissionsDoesNotCreateRunOrNeedApiKey()throws Exception{
        android.content.Intent intent=CandidateCapture.intent(RuntimeEnvironment.getApplication(),row(5));CandidateCaptureActivity activity=Robolectric.buildActivity(CandidateCaptureActivity.class,intent).setup().get();assertFalse(activity.isFinishing());assertEquals(0,store.read().getJSONArray("runs").length());assertFalse(new GeminiClient(activity).hasKey());activity.finish();
    }
    @Test public void savedWebResultOpensWithoutRestartingCapture()throws Exception{
        JSONObject r=CandidateCapture.newRun(store,row(6));String id=r.getString("id");r.put("web_result",new JSONObject().put("kind","google_search_excerpt").put("query","2330 台積電").put("queried",100).put("snippets",new JSONArray().put("查詢摘要")));store.updateRun(r);
        CandidateCaptureActivity a=Robolectric.buildActivity(CandidateCaptureActivity.class,new android.content.Intent(RuntimeEnvironment.getApplication(),CandidateCaptureActivity.class).putExtra(CandidateCapture.RUN,id)).setup().get();assertEquals(1,store.read().getJSONArray("runs").length());assertFalse(ChipCaptureService.running());a.finish();
    }
}
