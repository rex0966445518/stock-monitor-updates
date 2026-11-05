package tw.rex.link;
public final class PairingRules {
 public static boolean allowed(long now,long until,String code,String selected,String token,String phone,String claimed,String pin,String offered){
  if(now>=until||!phone.equals(claimed))return false;
  if(!offered.isEmpty())return !token.isEmpty()&&phone.equals(selected)&&token.equals(offered);
  return !code.isEmpty()&&code.equals(pin);
 }
}
