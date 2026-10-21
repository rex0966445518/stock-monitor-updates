package com.rex.mitakecompanion.portfolio;

import android.graphics.Rect;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ChipVideoProfileTest {
    // Distinct screen text transcribed from 3892.mp4 at 14,22,30,38,40,44 seconds.
    final String[] screens={
        "日分價量 日 週 月 5分 還原日 開100.5 高101.0 低96.6 收98.2 成交量 MACD DIF:3.55",
        "統計天數 自訂 1 3 5 10 近一日 主力動向 大買 籌碼集中 1403張 買方Top15 賣方Top15",
        "外資連買放量帶動法人偏多 三大法人 外資 投信 自營商 八大行庫 三大法人買賣超 日期",
        "多方優勢 多方 符合33項 空方 符合3項 籌碼面 外資連買超11天 主力今日買超30.4%",
        "主力連續買超加速 主力買賣超 買賣家數差 籌碼集中 日期 買賣超 家數差 5日集中 20日集中",
        "大戶持股 > 400 600 800 散戶持股 < 50 100 200 400 張數比例 持股人數 大戶增減"
    };
    @Test public void eachRecordedBodyQualifiesOnlyItsOwnPage(){
        for(int i=0;i<6;i++)for(int j=0;j<6;j++)assertEquals(i+" vs "+j,i==j,ChipVideoProfile.bodyMatches(screens[i],PortfolioStore.PAGES[j]));
        for(String page:PortfolioStore.PAGES)assertFalse(ChipVideoProfile.bodyMatches("K線 籌碼日報 法人 多空 主力 大戶",page));
    }
    @Test public void horizontalNavigationCanRecoverEarlierTabs(){
        assertFalse(ChipVideoProfile.scrollForward("K線",Arrays.asList("法人","多空","主力","新聞","健檢","大戶")));
        assertTrue(ChipVideoProfile.scrollForward("大戶",Arrays.asList("籌碼日報","達人觀點","法人","多空","主力")));
        assertFalse(ChipCaptureService.pageLabel("法人目標","法人"));
    }
    @Test public void bottomIndicatorDuplicatesAreExcludedAcrossResolutions(){
        for(int scale:new int[]{1,2,3}){Rect screen=new Rect(0,0,720*scale,1600*scale);
            assertTrue(ChipCaptureService.inTabStrip(new Rect(100*scale,370*scale,200*scale,440*scale),screen));
            assertFalse(ChipCaptureService.inTabStrip(new Rect(100*scale,1500*scale,200*scale,1560*scale),screen));
        }
    }
    @Test public void stockSearchDoesNotChooseSimilarCodesOrQuestionCards(){
        assertTrue(ChipVideoProfile.stockTitle("宏正 6277"));assertTrue(ChipVideoProfile.stockTitle("台積電2330"));
        assertTrue(ChipVideoProfile.stockResult("6277 宏正","6277"));assertTrue(ChipVideoProfile.stockResult("6277","6277"));
        for(String s:new String[]{"16277 宏正","62770 宏正","6277（宏正）最近三季的營收與毛利率趨勢如何？","6277 宏正 最近三季的營收與毛利率趨勢如何？"})assertFalse(s,ChipVideoProfile.stockResult(s,"6277"));
        assertFalse(ChipVideoProfile.stockTitle("下單"));
    }
}
