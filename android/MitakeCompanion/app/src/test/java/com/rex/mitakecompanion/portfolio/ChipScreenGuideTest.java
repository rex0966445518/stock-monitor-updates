package com.rex.mitakecompanion.portfolio;
import android.graphics.Rect;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ChipScreenGuideTest {
    ChipScreenGuide.Line l(String text,int x,int y){return new ChipScreenGuide.Line(text,new Rect(x,y,x+100,y+36));}
    ChipScreenGuide.Action plan(String code,String page,ChipScreenGuide.Line... rows){return ChipScreenGuide.plan(Arrays.asList(rows),720,1600,code,page);}
    @Test public void iconOnlyHomeRequiresSelfSelectedGridAndHeaderAnchors(){
        ChipScreenGuide.Action a=plan("6277","K線",l("即時",220,100),l("盤後",330,100),l("討論",440,100),l("自選股",130,1500));assertEquals("search",a.kind);assertTrue(a.box.centerX()<80);assertEquals(118,a.box.centerY());
        assertNull(plan("6277","K線",l("即時",220,100),l("盤後",330,100),l("庫存",130,1500)));
    }
    @Test public void searchInputDoesNotRequireStockAlreadyOpen(){assertEquals("input",plan("6277","K線",l("搜尋",330,100),l("股票代號 / 名稱",100,200),l("熱門搜尋",30,300)).kind);}
    @Test public void matchingQueryAtTopIsNotMistakenForStockHeader(){assertEquals("result",plan("6277","K線",l("搜尋",330,100),l("6277",100,200),l("個股",80,310),l("6277 宏正",30,470),l("大家都在搜",30,570)).kind);}
    @Test public void questionsAndSimilarStocksAreNeverClicked(){assertNull(plan("6277","K線",l("搜尋",330,100),l("6277",100,200),l("個股",80,310),l("62770 宏正",30,470),l("大家都在搜",30,570),l("6277 宏正 最近三季的營收與毛利率趨勢如何？",30,650)));}
    @Test public void earlierKTabIsRevealedWithRightSwipe(){ChipScreenGuide.Action a=plan("6277","K線",l("宏正 6277",300,90),l("法人",20,380),l("多空",130,380),l("主力",230,380));assertEquals("swipe",a.kind);assertFalse(a.forward);}
    @Test public void correctBodyCanBeCapturedWithoutSelectedAccessibilityFlag(){ChipScreenGuide.Action a=plan("6277","法人",l("宏正 6277",300,90),l("法人",20,380),l("多空",130,380),l("三大法人買賣超",30,650),l("外資 投信 自營商",30,700));assertEquals("capture",a.kind);assertEquals("search",plan("2330","法人",l("宏正 6277",300,90),l("法人",20,380),l("三大法人買賣超 外資 投信",30,650)).kind);}
    @Test public void navWordsAloneDoNotBecomeSixValidScreens(){assertEquals("tab",plan("6277","法人",l("宏正 6277",300,90),l("法人",20,380),l("多空",130,380)).kind);}
}
