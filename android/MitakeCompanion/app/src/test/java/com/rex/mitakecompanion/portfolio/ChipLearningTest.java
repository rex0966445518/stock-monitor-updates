package com.rex.mitakecompanion.portfolio;
import android.graphics.Rect;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ChipLearningTest {
    ChipPositionMemory memory;
    final String report="6277 主力動向 大買 籌碼集中 1403張 成交量4609張";
    final String k="日分價量 還原日 成交量 MACD";
    @Before public void reset(){memory=new ChipPositionMemory(RuntimeEnvironment.getApplication());memory.clear();}
    ChipScreenGuide.Line l(String s,int x,int y){return new ChipScreenGuide.Line(s,new Rect(x,y,x+100,y+32));}
    @Test public void reportDoesNotRequirePerfectTop15Ocr(){assertTrue(ChipVideoProfile.bodyMatches(report,"籌碼日報"));assertFalse(ChipVideoProfile.bodyMatches(report,"主力"));}
    @Test public void simplifiedOcrAndTopLetterConfusionRemainRecognizable(){assertTrue(ChipVideoProfile.bodyMatches("主力动向 筹码集中 买方TOPI5 卖方T0PL5","籌碼日報"));}
    @Test public void mixedTransitionFrameDoesNotBecomeValidPage(){assertEquals("",ChipVideoProfile.detectedPage(k+report));assertFalse(ChipVideoProfile.bodyMatches(k+report,"籌碼日報"));}
    @Test public void navToleratesOneCharacterButNeverConfusesCode(){assertTrue(ChipVideoProfile.tabLabel("籌碼日抱","籌碼日報"));assertTrue(ChipVideoProfile.tabLabel("筹码日报","籌碼日報"));assertFalse(ChipVideoProfile.tabLabel("法人目標","法人"));assertFalse(ChipCaptureService.exactCode("6278","6277"));}
    @Test public void previousKFrameStillRequestsReportInsteadOfSavingAgain(){ChipScreenGuide.Action a=ChipScreenGuide.plan(Arrays.asList(l("宏正6277",250,90),l("K線",10,380),l("籌碼日報",120,380),l(k,20,530)),720,1600,"6277","籌碼日報");assertEquals("tab",a.kind);}
    @Test public void bodyAndHeaderCanConfirmWithoutReadableTabSelectedState(){ChipScreenGuide.Action a=ChipScreenGuide.plan(Arrays.asList(l("宏正6277",250,90),l(report,20,530)),720,1600,"6277","籌碼日報");assertEquals("capture",a.kind);}
    @Test public void largerHeaderUsesCorrectClusterNotBottomIndicators(){List<ChipScreenGuide.Line> lines=Arrays.asList(l("K線",10,650),l("籌碼日報",120,650),l("法人",300,650),l("主力",100,1500),l("大戶",300,1500));Rect a=ChipScreenGuide.anchor(lines,1600,"籌碼日報");assertNotNull(a);assertEquals(650,a.top);assertNull(ChipScreenGuide.anchor(lines,1600,"大戶"));}
    @Test public void cacheRequiresTwoSuccessfulPageConfirmations(){Rect a=new Rect(120,380,240,420);memory.confirm("籌碼日報",a,720,1600,report,"6277");assertNull(memory.hint("籌碼日報",720,1600));memory.confirm("籌碼日報",a,720,1600,report,"6277");assertNotNull(memory.hint("籌碼日報",720,1600));}
    @Test public void wrongPageCannotTeachPosition(){Rect a=new Rect(120,380,240,420);for(int i=0;i<3;i++)memory.confirm("籌碼日報",a,720,1600,"6277 "+k,"6277");assertNull(memory.hint("籌碼日報",720,1600));}
    @Test public void layoutChangeDoesNotReuseOldPosition(){Rect a=new Rect(120,380,240,420);for(int i=0;i<2;i++)memory.confirm("籌碼日報",a,720,1600,report,"6277");assertNull(memory.hint("籌碼日報",1600,720));assertNull(memory.hint("籌碼日報",1080,2400));}
    @Test public void twoFailedCropChecksForgetStaleLocation(){Rect a=new Rect(120,380,240,420);for(int i=0;i<2;i++)memory.confirm("籌碼日報",a,720,1600,report,"6277");memory.miss("籌碼日報",720,1600);assertNotNull(memory.hint("籌碼日報",720,1600));memory.miss("籌碼日報",720,1600);assertNull(memory.hint("籌碼日報",720,1600));}
    @Test public void resumeKeepsKAndSkipsOtherAlreadyCollectedPages()throws Exception{JSONObject pages=new JSONObject().put("K線",new JSONObject()).put("法人",new JSONObject()),run=new JSONObject().put("pages",pages);assertEquals(1,ChipRecovery.nextMissing(run));pages.put("籌碼日報",new JSONObject());assertEquals(3,ChipRecovery.nextMissing(run));for(String page:PortfolioStore.PAGES)pages.put(page,new JSONObject());assertEquals(6,ChipRecovery.nextMissing(run));}
    @Test public void retryChangesToSwipeAfterTwoFailedTapsAndIsBounded(){ChipRecovery r=new ChipRecovery();assertFalse(r.shouldSwipe("K線","籌碼日報"));r.tapped();r.tapped();assertTrue(r.shouldSwipe("K線","籌碼日報"));assertFalse(r.shouldSwipe("","籌碼日報"));assertFalse(r.shouldSwipe("籌碼日報","籌碼日報"));for(int i=0;i<4;i++)r.swiped();assertTrue(r.exhausted());r.reset();assertFalse(r.exhausted());}
}
