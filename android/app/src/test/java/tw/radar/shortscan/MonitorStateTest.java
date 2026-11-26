package tw.radar.shortscan;
import org.junit.Test;import static org.junit.Assert.*;import org.json.*;
public class MonitorStateTest {
 @Test public void simulationNeverLive(){MonitorState m=new MonitorState();m.reset(true,100);assertTrue(m.state(100,true,true,true).contains("模擬"));assertFalse(m.engineFresh(100));}
 @Test public void heartbeatAloneNotStrategy() throws Exception {MonitorState m=new MonitorState();m.reset(false,100);m.socket=true;m.packet=200;m.report(new JSONObject().put("feed_ok",true),200);assertTrue(m.state(200,true,true,true).contains("待驗證"));assertEquals(-1,m.evaluations);}
 @Test public void evidenceExpires() throws Exception {MonitorState m=new MonitorState();m.reset(false,100);m.socket=true;m.packet=200;m.report(new JSONObject().put("feed_ok",true),200);m.engine(new JSONObject().put("demo",false).put("trade_date","2026-11-26").put("engine_epoch","abc").put("evaluation_count",3).put("quote_rate_per_second",4).put("last_trade_ms",1).put("last_evaluation_ms",2),200);assertEquals("收到策略運算回報",m.state(200,true,true,true));assertFalse(m.engineFresh(16000));m.invalidate();assertFalse(m.engineFresh(200));}
 @Test public void noFakeCounters() throws Exception {MonitorState m=new MonitorState();m.reset(false,100);m.socket=true;m.report(new JSONObject().put("feed_ok",true).put("demo",true),100);assertEquals(-1,m.evaluations);assertEquals(0,m.packets);}
}