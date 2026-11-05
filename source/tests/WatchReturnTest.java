import tw.rex.link.WatchReturn;
import tw.rex.link.WatchRules;
public class WatchReturnTest {
 static int n;static void ok(boolean v,String label){if(!v)throw new AssertionError(label);n++;System.out.println("PASS "+label);}
 static class Fake implements WatchReturn.Surface {
  String calls="";boolean viewing=true,removed=true,restored=true;boolean pending=false,cover=true;int plays=0;WatchRules.Phase phase=WatchRules.Phase.WATCHING;
  public boolean watching(){return viewing;}
  public boolean removeCover(){calls+="remove ";if(removed)cover=false;return removed;}
  public void closeNotice(){calls+="notice ";}
  public boolean restoreVideo(){calls+="video ";return restored;}
  public void releaseAudio(){calls+="focus ";}
  public void scheduleResume(){calls+="schedule ";pending=true;}
  void deliver(){if(pending&&WatchReturn.mayResume(phase,cover))plays++;pending=false;}
 }
 public static void main(String[] args){
  Fake f=new Fake();ok(WatchReturn.reveal(f),"correct-answer handoff completes");
  ok(f.calls.equals("remove notice video focus schedule "),"remove overlay and prompt, restore video, release audio, then schedule play");
  ok(f.plays==0&&f.pending,"do not send play before the video task has time to return");f.deliver();ok(f.plays==1,"play once after return");f.deliver();ok(f.plays==1,"no repeated delayed playback");
  f=new Fake();f.viewing=false;ok(!WatchReturn.reveal(f)&&f.calls.isEmpty(),"wrong/locked/expired answer cannot dismiss anything");
  f=new Fake();f.removed=false;ok(!WatchReturn.reveal(f)&&f.calls.equals("remove ")&&!f.pending,"failed removal keeps video paused for retry");
  f=new Fake();f.restored=false;ok(!WatchReturn.reveal(f)&&f.calls.equals("remove notice video ")&&!f.pending,"failed task return does not send play");
  f=new Fake();WatchReturn.reveal(f);f.phase=WatchRules.Phase.PARENT_LOCK;f.deliver();ok(f.plays==0,"parent lock arriving during handoff cancels play");
  f=new Fake();WatchReturn.reveal(f);f.phase=WatchRules.Phase.QUESTION;f.cover=true;f.deliver();ok(f.plays==0,"expired reward arriving during handoff cancels play");
  f=new Fake();WatchReturn.reveal(f);f.phase=WatchRules.Phase.IDLE;f.deliver();ok(f.plays==0,"ending session cancels delayed play");
  f=new Fake();WatchReturn.reveal(f);f.cover=true;f.deliver();ok(f.plays==0,"remaining cover prevents play");
  for(WatchRules.Phase p:WatchRules.Phase.values())ok(!WatchReturn.mayResume(p,true),"visible overlay forbids resume: "+p);
  System.out.println(n+" checks passed");
 }
}
