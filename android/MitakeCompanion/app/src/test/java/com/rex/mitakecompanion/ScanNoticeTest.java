package com.rex.mitakecompanion;
import android.app.*;
import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=32)
public class ScanNoticeTest {
    JSONObject notice()throws Exception{return new JSONObject().put("kind","ranking_skipped").put("ranking","開盤強勢").put("cycle",2).put("reason","找不到排行").put("ts",100).put("seq",4);}
    @Test public void cursorAgeSessionAndTypeAreChecked()throws Exception{
        JSONObject e=notice();assertTrue(ScanNotice.eligible(e,101,true,3));
        assertFalse(ScanNotice.eligible(e,101,true,4));
        assertTrue(ScanNotice.eligible(e,101,false,100));
        assertFalse(ScanNotice.eligible(e,401,false,0));
        assertFalse(ScanNotice.eligible(e,99,false,0));
        assertFalse(ScanNotice.eligible(e,Double.NaN,false,0));
        assertFalse(ScanNotice.eligible(e.put("kind","candidate"),101,false,0));
    }
    @Test public void notificationUsesOwnChannelAndDoesNotPolluteSignalHistory()throws Exception{
        Context c=RuntimeEnvironment.getApplication();BridgeService.prefs(c).edit().clear().commit();
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("scan_errors","排行異常",NotificationManager.IMPORTANCE_HIGH));
        JSONObject data=new JSONObject().put("session","new").put("server_time",101).put("notices",new JSONArray().put(notice()));
        ScanNotice.notify(c,data,"old",100);
        Notification[] ns=java.util.Arrays.stream(manager.getActiveNotifications()).map(n->n.getNotification()).toArray(Notification[]::new);
        assertEquals(1,ns.length);assertEquals("scan_errors",ns[0].getChannelId());
        assertTrue(ns[0].extras.getString(Notification.EXTRA_TITLE).contains("開盤強勢"));
        assertTrue(ns[0].extras.getString(Notification.EXTRA_TEXT).contains("找不到排行"));
        assertFalse(BridgeService.prefs(c).contains("last_signal_title"));
        manager.cancelAll();ScanNotice.notify(c,data,"new",4);assertEquals(0,manager.getActiveNotifications().length);
    }
}
