package com.rex.mitakecompanion;

import android.content.SharedPreferences;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class SignalHistoryTest {
    SharedPreferences prefs;
    static final double T=1792483200;
    @Before public void clear(){prefs=BridgeService.prefs(RuntimeEnvironment.getApplication());prefs.edit().clear().commit();BridgeService.latest=null;BridgeService.receivedAt=0;}
    JSONObject row(int n,double ts)throws Exception{return new JSONObject().put("code","2330").put("name","台積電").put("matched",n).put("total",6).put("ts",ts).put("price",100).put("valid",true);}
    JSONObject snapshot(JSONObject... rows)throws Exception{JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);return new JSONObject().put("rows",a).put("server_time",T+10).put("active",true);}
    @Test public void downgradeWithoutServerPeakAndOmissionSurviveRestart()throws Exception{
        SignalHistory.merge(prefs,snapshot(row(5,T)));
        JSONArray result=SignalHistory.merge(prefs,snapshot(row(4,T+1)));
        assertEquals(4,result.getJSONObject(0).getInt("matched"));assertEquals(5,result.getJSONObject(0).getInt("peak"));
        assertFalse(result.getJSONObject(0).getBoolean("history_missing"));
        result=SignalHistory.merge(prefs,snapshot());assertEquals(1,result.length());assertTrue(result.getJSONObject(0).getBoolean("history_missing"));
        result=SignalHistory.merge(BridgeService.prefs(RuntimeEnvironment.getApplication()),null);
        assertEquals(4,result.getJSONObject(0).getInt("matched"));assertEquals(5,result.getJSONObject(0).getInt("peak"));
    }
    @Test public void crossDayRecordsRemainSeparateAndOlderSnapshotCannotUndoDowngrade()throws Exception{
        SignalHistory.merge(prefs,snapshot(row(6,T)));SignalHistory.merge(prefs,snapshot(row(4,T+2)));
        JSONArray result=SignalHistory.merge(prefs,snapshot(row(5,T+1)));
        assertEquals(4,result.getJSONObject(0).getInt("matched"));assertTrue(result.getJSONObject(0).getBoolean("history_missing"));
        result=SignalHistory.merge(prefs,snapshot(row(5,T+86400)));
        assertEquals(2,result.length());assertNotEquals(result.getJSONObject(0).getString("history_key"),result.getJSONObject(1).getString("history_key"));
        assertEquals(6,result.getJSONObject(1).getInt("peak"));
    }
    @Test public void samePollEventSeedsHistoryBeforeFourAndDemoNeverPersists()throws Exception{
        JSONObject data=snapshot(row(4,T+1)).put("events",new JSONArray().put(row(5,T)));
        JSONArray result=SignalHistory.merge(prefs,data);assertEquals(1,result.length());assertEquals(4,result.getJSONObject(0).getInt("matched"));assertEquals(5,result.getJSONObject(0).getInt("peak"));
        prefs.edit().clear().commit();assertEquals(0,SignalHistory.merge(prefs,snapshot(row(4,T),row(6,T).put("demo",true))).length());
    }
    @Test public void cachedPeakMigratesAndRecoveredCandidateUpdatesSingleDailyCard()throws Exception{
        SignalHistory.merge(prefs,snapshot(row(4,T).put("peak",5).put("valid",false)));
        JSONArray result=SignalHistory.merge(prefs,snapshot(row(6,T+1)));
        assertEquals(1,result.length());assertEquals(6,result.getJSONObject(0).getInt("peak"));assertEquals(6,result.getJSONObject(0).getInt("matched"));
        assertFalse(AlertPolicy.eligible(SignalHistory.merge(prefs,snapshot(row(4,T+2))).getJSONObject(0),T+3,true));
    }
    String text(View v){if(v instanceof TextView)return ((TextView)v).getText().toString();StringBuilder s=new StringBuilder();if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s.append(text(((ViewGroup)v).getChildAt(i))).append('\n');return s.toString();}
    @Test public void weakTabShowsFourAfterDisappearanceAndActivityRecreation()throws Exception{
        SignalHistory.merge(prefs,snapshot(row(5,T)));SignalHistory.merge(prefs,snapshot(row(4,T+1)));
        BridgeService.latest=snapshot();
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        a.filter="轉弱";a.last="";a.refresh();
        assertTrue(text(a.cards).contains("4/6"));assertTrue(text(a.cards).contains("曾達 5/6"));assertTrue(text(a.cards).contains("已轉弱，保留紀錄"));assertTrue(text(a.cards).contains("非即時"));
        assertEquals("0",a.counts5.getText().toString());a.finish();
        BridgeService.latest=null;
        MainActivity b=Robolectric.buildActivity(MainActivity.class).setup().get();
        assertTrue(text(b.cards).contains("4/6"));assertEquals(1,b.cards.getChildCount());b.finish();
    }
    @Test public void omittedFiveIsNotCountedLiveAndHistoryDoesNotEnterAlerts()throws Exception{
        SignalHistory.merge(prefs,snapshot(row(5,T)));
        prefs.edit().putBoolean("enabled",true).apply();BridgeService.receivedAt=android.os.SystemClock.elapsedRealtime()+1;BridgeService.latest=snapshot();
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();a.refresh();
        assertEquals("0",a.counts5.getText().toString());assertTrue(text(a.cards).contains("本輪未更新"));
        assertEquals("",prefs.getString("last_signal_title",""));assertEquals(0,BridgeService.latest.getJSONArray("rows").length());a.finish();
    }
}
