package com.rex.mitakecompanion.portfolio;
import android.content.*;
import android.database.Cursor;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class GoogleAiCaptureTest {
    Context c;PortfolioStore store;CaptureDocuments provider;
    @Before public void setup()throws Exception{c=RuntimeEnvironment.getApplication();store=PortfolioStore.get(c);Files.deleteIfExists(new File(store.root(),"portfolio.json").toPath());CaptureDocuments.close(c);provider=Robolectric.buildContentProvider(CaptureDocuments.class).create().get();}
    String complete()throws Exception{JSONObject r=CandidateCapture.newRun(store,new JSONObject().put("code","6277").put("name","宏正").put("matched",5));String id=r.getString("id");for(String page:PortfolioStore.PAGES){File f=store.imageFile(id,page);Files.write(f.toPath(),new byte[]{1,2,3});store.attach(id,page,f,"fixture","6277");}return id;}
    @Test public void exactAiOriginAndModeRequired(){assertTrue(GoogleAiCapture.aiAddress("https://www.google.com/ai"));assertTrue(GoogleAiCapture.aiAddress("www.google.com/search?udm=50&q=test"));for(String s:new String[]{"www.google.com/search?q=test","google.com.evil.test/ai","https://evil.test/ai","http://google.com/ai","www.google.com/search?x=udm=50"})assertFalse(s,GoogleAiCapture.aiAddress(s));}
    @Test public void attachmentVerificationRequiresAllSixActualFilenames(){List<String> labels=new ArrayList<>();for(int i=0;i<6;i++)labels.add(CaptureDocuments.name(i));assertTrue(GoogleAiCapture.sixAttachments(labels));labels.remove(3);labels.add("4-其他.jpg");assertFalse(GoogleAiCapture.sixAttachments(labels));}
    @Test public void answerNeverIncludesPromptOrAccountUi(){String p=GoogleAiCapture.prompt("task","6277","宏正");String reply="6277 宏正：依據K線及籌碼日報可見資料，應先核對圖片日期、法人動向與大戶變化，圖片不足的部分不能推估，也不能保證獲利。";assertEquals(Collections.singletonList(reply),GoogleAiCapture.answers(Arrays.asList(p,"Google 帳戶：使用者 personal@example.com，這是私人帳戶資訊不應當成分析回覆。",reply),"雷達圖組task",p));}
    @Test public void providerExposesExactlyOneRunSixReadOnlyDocuments()throws Exception{String id=complete();CaptureDocuments.offer(c,id);try(Cursor rows=provider.queryChildDocuments(id,null,(String)null)){assertEquals(6,rows.getCount());}assertThrows(FileNotFoundException.class,()->provider.openDocument(id+":0","w",null));assertThrows(FileNotFoundException.class,()->provider.queryDocument("../portfolio.json",null));assertThrows(FileNotFoundException.class,()->provider.queryChildDocuments("other-run",null,(String)null));}
    @Test public void providerClosesAfterStopOrExpiry()throws Exception{String id=complete();CaptureDocuments.offer(c,id);CaptureDocuments.close(c);assertThrows(FileNotFoundException.class,()->provider.openDocument(id+":0","r",null));CaptureDocuments.offer(c,id);c.getSharedPreferences("capture_upload",0).edit().putLong("until",1).commit();assertThrows(FileNotFoundException.class,()->provider.queryDocument(id+":0",null));}
    @Test public void testCaptureHasNoFakeHoldingOrAlertHistory()throws Exception{Intent i=CandidateCapture.testIntent(c,"6277","宏正");JSONObject row=new JSONObject(i.getStringExtra(CandidateCapture.EXTRA)),r=CandidateCapture.newRun(store,row);assertEquals("manual_capture_test",r.getString("source"));assertEquals("google_ai_images",r.getString("web_mode"));assertEquals("",r.getJSONObject("holding").getString("price"));assertEquals(0,store.read().getJSONArray("holdings").length());assertFalse(r.getBoolean("auto_analyze"));}

    @Test public void iconButtonsUseComposerRowAcrossSizes(){
        for(int scale:new int[]{1,2,3}){android.graphics.Rect box=new android.graphics.Rect(20*scale,530*scale,670*scale,922*scale);
            List<android.graphics.Rect> buttons=Arrays.asList(new android.graphics.Rect(42*scale,834*scale,128*scale,898*scale),new android.graphics.Rect(410*scale,834*scale,452*scale,898*scale),new android.graphics.Rect(480*scale,834*scale,530*scale,898*scale),new android.graphics.Rect(562*scale,834*scale,648*scale,898*scale));
            assertEquals(0,GoogleAiCapture.iconIndex(box,buttons,false));assertEquals(3,GoogleAiCapture.iconIndex(box,buttons,true));}
        assertEquals(-1,GoogleAiCapture.iconIndex(new android.graphics.Rect(0,0,600,400),Collections.singletonList(new android.graphics.Rect(550,300,590,350)),true));
    }
    @Test public void iconAndLocalizedActionsRecognized(){assertTrue(GoogleAiCapture.actionLabel("＋",false));assertTrue(GoogleAiCapture.actionLabel("Add files and images",false));assertTrue(GoogleAiCapture.actionLabel("向上箭頭",true));assertTrue(GoogleAiCapture.actionLabel("Send message",true));assertFalse(GoogleAiCapture.actionLabel("Google 帳戶",true));}
    @Test public void sendRequiresChromeReadingSixAndReadyAttachments(){
        List<String> files=new ArrayList<>();for(int i=0;i<6;i++)files.add(CaptureDocuments.name(i));
        assertFalse(GoogleAiCapture.attachmentsReady(files,0));assertFalse(GoogleAiCapture.attachmentsReady(files,5));assertTrue(GoogleAiCapture.attachmentsReady(files,6));
        files.add("上傳中");assertFalse(GoogleAiCapture.attachmentsReady(files,6));
        files.clear();for(int i=0;i<6;i++)files.add("Remove image "+i);assertTrue(GoogleAiCapture.attachmentsReady(files,6));files.remove(0);assertFalse(GoogleAiCapture.attachmentsReady(files,6));
    }
    @Test public void textOnlyGoogleResponseIsFailure(){assertTrue(GoogleAiCapture.missingImages("目前系統中並沒有收到或看到任何您上傳的圖片檔案。"));assertTrue(GoogleAiCapture.missingImages("No images attached"));assertFalse(GoogleAiCapture.missingImages("六張附圖中的法人與主力方向不同，應注意風險。"));}
    @Test public void readReceiptsCannotLeakAcrossRunsOrExpiry()throws Exception{String id=complete();CaptureDocuments.offer(c,id);c.getSharedPreferences("capture_upload",0).edit().putInt("chrome_reads",63).commit();assertEquals(6,CaptureDocuments.chromeReads(c,id));assertEquals(0,CaptureDocuments.chromeReads(c,"other"));CaptureDocuments.offer(c,id);assertEquals(0,CaptureDocuments.chromeReads(c,id));}
    @Test public void repeatedUnknownVisualStopsWithAllSixImagesRetained()throws Exception{String id=complete();GoogleAiCapture task=new GoogleAiCapture(c,id,"6277","宏正");for(int i=0;i<4;i++)task.visualResult(null,new android.graphics.Rect(0,0,720,1600),720,1600,false);assertTrue(task.step(null));assertTrue(store.run(id).getString("status").contains("六張"));assertEquals(6,store.run(id).getJSONObject("pages").length());assertFalse(store.run(id).optBoolean("ai_submit_attempted"));}
}
