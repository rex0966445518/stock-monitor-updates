package com.rex.mitakecompanion;

import android.content.*;
import android.widget.*;
import android.view.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ReconnectTest {
    static final String ADDRESS="http://192.168.1.20:8765",TOKEN="b".repeat(48);
    SharedPreferences prefs;
    @Before public void clear(){prefs=BridgeService.prefs(RuntimeEnvironment.getApplication());prefs.edit().clear().commit();BridgeService.latest=null;BridgeService.receivedAt=0;}
    void save(boolean enabled){prefs.edit().putString("address",ADDRESS).putString("token",TOKEN).putBoolean("enabled",enabled).putString("session","existing-session").putLong("seq",42).putString("snapshot","{\"rows\":[]}").putString("signal_history","[]").putString("alert_receipts","existing-receipts").commit();}
    Intent started(){return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();}
    void preserved(){assertEquals("existing-session",prefs.getString("session",""));assertEquals(42,prefs.getLong("seq",0));assertEquals("{\"rows\":[]}",prefs.getString("snapshot",""));assertEquals("existing-receipts",prefs.getString("alert_receipts",""));assertEquals(TOKEN,prefs.getString("token",""));}
    @Test public void openingAppRestoresSavedEnabledConnectionWithoutResettingCursor(){
        save(true);MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        Intent service=started();assertNotNull(service);assertEquals(BridgeService.class.getName(),service.getComponent().getClassName());
        a.refresh();assertTrue(a.savedComputer.getText().toString().contains(ADDRESS));assertFalse(a.savedComputer.getText().toString().contains(TOKEN));preserved();a.finish();
    }
    @Test public void explicitStopDoesNotAutoResumeButOneTapReusesPairing(){
        save(false);MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();assertNull(started());
        assertTrue(a.resumeSaved(true));assertTrue(prefs.getBoolean("enabled",false));assertEquals(BridgeService.RETRY,started().getAction());preserved();a.finish();
    }
    @Test public void automaticSwitchIsHonouredAndDoesNotBlockManualResume(){
        save(true);prefs.edit().putBoolean("auto_reconnect",false).commit();
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();assertNull(started());assertFalse(a.resumeSaved(false));
        assertTrue(a.resumeSaved(true));assertNotNull(started());assertFalse(prefs.getBoolean("auto_reconnect",true));preserved();a.finish();
    }
    @Test public void unpairedAndCorruptSavedTargetsNeverStartAService(){
        prefs.edit().putBoolean("enabled",true).putString("address","http://8.8.8.8").putString("token",TOKEN).commit();
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();assertNull(started());assertNull(Pairing.saved(prefs));assertFalse(a.resumeSaved(false));a.finish();
    }
    @Test public void blankFormTokenReusesOnlySameNormalizedAddress(){
        save(false);assertEquals(TOKEN,Pairing.fromForm(prefs," "+ADDRESS+"/ ","").token);
        assertThrows(IllegalArgumentException.class,()->Pairing.fromForm(prefs,"http://192.168.1.21:8765",""));
        assertEquals("c".repeat(48),Pairing.fromForm(prefs,"http://192.168.1.21:8765","c".repeat(48)).token);
        prefs.edit().clear().commit();assertThrows(IllegalArgumentException.class,()->Pairing.fromForm(prefs,ADDRESS,""));
    }
    @Test public void pairingFormReusesSavedTokenAndSamePairDoesNotClearHistory(){
        save(false);MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();a.showPairing();
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(started());preserved();a.finish();
    }
    @Test public void newDeepLinkWaitsForConfirmationBeforeStartingSavedConnection(){
        save(true);Intent link=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("mitake://pair?address=http%3A%2F%2F192.168.1.21%3A8765&token="+"c".repeat(48)));
        MainActivity a=Robolectric.buildActivity(MainActivity.class,link).setup().get();assertNull(started());assertTrue(a.pairingPrompt);preserved();a.finish();
    }
}
