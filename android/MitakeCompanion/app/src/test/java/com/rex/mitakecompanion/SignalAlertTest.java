package com.rex.mitakecompanion;

import android.content.*;
import android.os.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class SignalAlertTest {
    Context context;SharedPreferences prefs;HapticAnnouncer haptics;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();prefs=BridgeService.prefs(context);prefs.edit().clear().commit();}
    @After public void cleanup(){if(haptics!=null)haptics.close();}
    JSONObject event(int level,double ts)throws Exception{return new JSONObject().put("code","2330").put("name","台積電").put("matched",level).put("total",6).put("valid",true).put("confirmed",false).put("ts",ts);}
    @Test public void firstSnapshotNotifiesButContinuousQuotesAndReconnectDoNot()throws Exception{
        SignalGate gate=new SignalGate(prefs);long wall=1792540800000L;
        assertTrue(gate.accept(event(5,100),101,true,false,wall));
        assertFalse(gate.accept(event(5,100),101,true,true,wall+1));
        assertFalse(gate.accept(event(5,103),104,true,false,wall+3000));
        assertFalse(new SignalGate(prefs).accept(event(5,100),102,true,true,wall+4000));
        assertTrue(gate.accept(event(6,105),106,true,false,wall+5000));
        assertFalse(gate.accept(event(5,106),107,true,false,wall+6000));
        assertEquals("6/6 · 台積電 2330",prefs.getString("last_signal_title",""));
    }
    @Test public void explicitServerRepeatIsRateLimitedAndConfirmationUpgradeCanNotify()throws Exception{
        SignalGate gate=new SignalGate(prefs);long wall=1792540800000L;
        assertTrue(gate.accept(event(6,100),101,true,true,wall));
        assertTrue(gate.accept(event(6,102).put("confirmed",true),103,true,true,wall+2000));
        assertFalse(gate.accept(event(6,104).put("confirmed",true),105,true,true,wall+4000));
        assertTrue(gate.accept(event(6,165).put("confirmed",true),166,true,true,wall+65000));
        assertFalse(gate.accept(event(6,103),104,true,true,wall+66000));
    }
    @Test public void invalidExpiredPausedAndDemoRowsNeverConsumeReceipt()throws Exception{
        SignalGate gate=new SignalGate(prefs);long wall=1792540800000L;
        assertFalse(gate.accept(event(5,100),116,true,true,wall));
        assertFalse(gate.accept(event(6,100),101,false,true,wall));
        assertFalse(gate.accept(event(6,100).put("valid",false),101,true,true,wall));
        assertFalse(gate.accept(DemoCards.rows().getJSONObject(0),101,true,false,wall));
        assertFalse(prefs.contains("alert_receipts"));
    }
    @Test public void directVibrationWorksWithoutNotificationPermissionAndSwitchStopsIt()throws Exception{
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Vibrator v=context.getSystemService(Vibrator.class);org.robolectric.Shadows.shadowOf(v).setHasVibrator(true);
        haptics=new HapticAnnouncer(context);haptics.announce(event(5,100),101,true);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(130));
        assertTrue(org.robolectric.Shadows.shadowOf(v).isVibrating());assertEquals(5,prefs.getInt("last_vibration_level",0));
        prefs.edit().putBoolean("vibration_enabled",false).commit();org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(org.robolectric.Shadows.shadowOf(v).isVibrating());
        haptics.announce(event(6,102),103,true);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(130));assertEquals(5,prefs.getInt("last_vibration_level",0));
    }
    @Test public void burstUsesStrongestLevelAndExplicitTestDoesNotChangeDisabledPreference()throws Exception{
        Vibrator v=context.getSystemService(Vibrator.class);org.robolectric.Shadows.shadowOf(v).setHasVibrator(true);haptics=new HapticAnnouncer(context);
        haptics.announce(event(6,100),101,true);haptics.announce(event(5,100).put("code","2317"),101,true);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(130));assertEquals(6,prefs.getInt("last_vibration_level",0));
        prefs.edit().putBoolean("vibration_enabled",false).commit();org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        haptics.test();org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(130));assertTrue(org.robolectric.Shadows.shadowOf(v).isVibrating());assertFalse(prefs.getBoolean("vibration_enabled",true));
    }
}
