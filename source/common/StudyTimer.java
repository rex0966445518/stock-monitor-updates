package tw.rex.link;
/** Accumulates only active answering time, using a monotonic clock supplied by caller. */
public final class StudyTimer {
 private long accumulated,started; private boolean running;
 public void start(long now){accumulated=0;started=now;running=true;}
 public void pause(long now){if(running){accumulated+=Math.max(0,now-started);running=false;}}
 public void resume(long now){if(!running){started=now;running=true;}}
 public long elapsed(long now){return accumulated+(running?Math.max(0,now-started):0);}
 public void reset(long now){start(now);}
}
