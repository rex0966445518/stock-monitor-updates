package tw.rex.link;
/** Ordered handoff from the question window back to the existing video task. */
public final class WatchReturn {
 public interface Surface {
  boolean watching(); boolean removeCover(); void closeNotice(); boolean restoreVideo(); void releaseAudio(); void scheduleResume();
 }
 public static boolean reveal(Surface s){
  if(!s.watching()||!s.removeCover())return false;
  s.closeNotice();
  if(!s.restoreVideo())return false;
  s.releaseAudio();s.scheduleResume();return true;
 }
 public static boolean mayResume(WatchRules.Phase phase,boolean coverVisible){return phase==WatchRules.Phase.WATCHING&&!coverVisible;}
}
