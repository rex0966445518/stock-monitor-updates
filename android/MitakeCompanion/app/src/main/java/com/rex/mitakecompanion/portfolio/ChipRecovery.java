package com.rex.mitakecompanion.portfolio;

import org.json.*;

/** Bounded retries and resume bookkeeping, independent of transient tab selected flags. */
final class ChipRecovery {
    private int taps,swipes,uncertain;
    void reset(){taps=swipes=uncertain=0;}
    boolean shouldSwipe(String observed,String target){return taps>=2&&!observed.isEmpty()&&!observed.equals(target)&&swipes<4;}
    void tapped(){taps++;}
    void swiped(){swipes++;taps=0;}
    void uncertain(){uncertain++;}
    boolean exhausted(){return swipes>=4||taps>=6||uncertain>=7;}
    String summary(){return "頁籤定位 "+taps+"次 · 翻頁 "+swipes+"次 · 重辨識 "+uncertain+"次";}
    static int nextMissing(JSONObject run){JSONObject pages=run.optJSONObject("pages");for(int i=0;i<PortfolioStore.PAGES.length;i++)if(pages==null||!pages.has(PortfolioStore.PAGES[i]))return i;return PortfolioStore.PAGES.length;}
}
