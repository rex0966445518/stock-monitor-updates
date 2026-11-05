import tw.rex.link.PairingRules;
public class PairingRulesTest {
 static int n;static void check(boolean b){if(!b)throw new AssertionError("case "+n);n++;}
 public static void main(String[] args){
  check(PairingRules.allowed(1,100,"123456","phone2","token","phone2","phone2","","token"));
  check(!PairingRules.allowed(100,100,"123456","phone2","token","phone2","phone2","","token"));
  check(!PairingRules.allowed(1,100,"123456","phone2","token","other","other","","token"));
  check(!PairingRules.allowed(1,100,"123456","phone2","token","phone2","other","","token"));
  check(!PairingRules.allowed(1,100,"123456","phone2","new","phone2","phone2","","old"));
  check(!PairingRules.allowed(1,100,"","","","phone2","phone2","",""));
  check(PairingRules.allowed(1,100,"123456","","","phone2","phone2","123456",""));
  check(!PairingRules.allowed(1,100,"123456","","","phone2","phone2","654321",""));
  System.out.println(n+" pairing authorization checks passed");
 }
}
