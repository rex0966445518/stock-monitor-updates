package tw.radar.shortscan;
import org.json.*;import java.util.*;
/** Separate local service activity, transport, upstream and strategy evidence. */
final class MonitorState {
 long start, tick, packet, report, evalReport, packets, signals, demoSignals, reconnects;
 boolean socket, demo, backendDemo, feedOk, hasFeed, tts, wake;
 int count=-1,expected=-1;long evaluations=-1,quoteRate=-1,lastQuote=-1,lastEval=-1;String source="",tradeDay="",epoch="",detail="";
 final ArrayDeque<String> events=new ArrayDeque<>();
 void reset(boolean d,long now){start=now;tick=now;packet=report=evalReport=packets=signals=demoSignals=reconnects=0;socket=false;demo=d;backendDemo=false;hasFeed=false;feedOk=false;count=expected=-1;clearEngine();events.clear();}
 void clearEngine(){evaluations=quoteRate=lastQuote=lastEval=-1;tradeDay=epoch="";evalReport=0;}
 void invalidate(){socket=false;hasFeed=false;feedOk=false;count=expected=-1;clearEngine();}
 void event(String t){events.addFirst(t);while(events.size()>15)events.removeLast();}
 void report(JSONObject j,long now){report=now;hasFeed=j.has("feed_ok")&&!j.isNull("feed_ok");feedOk=j.optBoolean("feed_ok");backendDemo=j.optBoolean("demo");source=j.optString("source","");count=j.optInt("count",-1);expected=j.optInt("expected_count",-1);if(!hasFeed||!feedOk||demo||backendDemo){clearEngine();return;}if(j.optJSONObject("telemetry")!=null)engine(j.optJSONObject("telemetry"),now);}
 void engine(JSONObject j,long now){if(demo||backendDemo||!socket||!hasFeed||!feedOk||!j.has("demo")||j.optBoolean("demo",true))return;String day=j.optString("trade_date"),id=j.optString("engine_epoch");long n=j.optLong("evaluation_count",-1),q=j.optLong("quote_rate_per_second",-1),lt=j.optLong("last_trade_ms",-1),le=j.optLong("last_evaluation_ms",-1);if(day.isEmpty()||id.isEmpty()||n<0||q<0||lt<=0||le<=0)return;if(day.equals(tradeDay)&&id.equals(epoch)&&evaluations>=0&&n<evaluations)return;tradeDay=day;epoch=id;evaluations=n;quoteRate=q;lastQuote=lt;lastEval=le;evalReport=now;}
 String state(long now,boolean active,boolean marketWindow,boolean configured){if(!active)return "已停止";if(demo)return "本機模擬｜非實盤";if(!configured)return "尚未設定行情後端";if(!socket||packet==0||now-packet>15000)return "後端未連線／重連中";if(backendDemo)return "後端模擬｜非實盤";if(!marketWindow)return "非例行交易時段";if(!hasFeed||report==0||now-report>15000)return "行情狀態未回報／逾時";if(!feedOk)return "行情中斷／未就緒";if(evalReport==0||now-evalReport>15000||evaluations<=0)return "行情已連線｜策略運算待驗證";return "收到策略運算回報";}
 boolean engineFresh(long now){return socket&&hasFeed&&feedOk&&!demo&&!backendDemo&&report>0&&now-report<=15000&&evalReport>0&&now-evalReport<=15000;}
}
