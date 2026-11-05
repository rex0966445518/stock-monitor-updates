import tw.rex.link.LearningStartRules;
import static tw.rex.link.LearningStartRules.Action.*;
public class LearningStartTest {
 static int n;static void ok(boolean v,String label){if(!v)throw new AssertionError(label);n++;System.out.println("PASS "+label);}
 public static void main(String[] args){
 ok(LearningStartRules.decide(false,false,false,false,false,false)==BASIC,"unpaired TV can start regular questions");
 ok(LearningStartRules.decide(false,false,true,false,false,false)==BASIC,"paired Xiaomi without Device Owner can start regular questions");
 ok(LearningStartRules.decide(false,false,true,true,false,false)==REQUIRE_PROTECTION,"managed paired TV still starts with protection");
 ok(LearningStartRules.decide(false,false,false,true,false,false)==BASIC,"unpaired TV does not enter kiosk without parent recovery");
 ok(LearningStartRules.decide(false,false,true,false,true,false)==REQUIRE_PROTECTION,"explicit strict mode cannot silently become basic");
 ok(LearningStartRules.decide(false,false,true,true,true,true)==PROTECTED,"already protected session can start");
 for(int bits=0;bits<32;bits++){
  boolean watch=(bits&1)!=0,paired=(bits&2)!=0,owner=(bits&4)!=0,strict=(bits&8)!=0,kiosk=(bits&16)!=0;
  ok(LearningStartRules.decide(true,watch,paired,owner,strict,kiosk)==PARENT_LOCK,"parent lock dominates state "+bits);
 }
 for(int bits=0;bits<16;bits++){
  boolean paired=(bits&1)!=0,owner=(bits&2)!=0,strict=(bits&4)!=0,kiosk=(bits&8)!=0;
  ok(LearningStartRules.decide(false,true,paired,owner,strict,kiosk)==WATCH_ACTIVE,"watch reward/overlay cannot be bypassed "+bits);
 }
 System.out.println(n+" checks passed");
 }
}
