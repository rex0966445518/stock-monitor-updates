package tw.rex.link;
/** Basic question practice does not require Device Owner; existing parent restrictions remain mandatory. */
public final class LearningStartRules {
 public enum Action { PARENT_LOCK, WATCH_ACTIVE, PROTECTED, REQUIRE_PROTECTION, BASIC }
 public static Action decide(boolean locked,boolean watchEnabled,boolean paired,boolean owner,boolean strict,boolean kioskActive){
  if(locked)return Action.PARENT_LOCK;
  if(watchEnabled)return Action.WATCH_ACTIVE;
  if(kioskActive)return Action.PROTECTED;
  if(strict||(paired&&owner))return Action.REQUIRE_PROTECTION;
  return Action.BASIC;
 }
}
