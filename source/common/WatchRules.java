package tw.rex.link;
public final class WatchRules {
 public enum Phase { IDLE, WATCHING, QUESTION, PARENT_LOCK }
 public static long rewardDeadline(long now,int seconds){if(seconds<1||seconds>3600)throw new IllegalArgumentException("觀看秒數須為 1～3600");long delta=seconds*1000L;if(now>Long.MAX_VALUE-delta)throw new ArithmeticException("time overflow");return now+delta;}
 public static Phase phase(boolean locked,boolean enabled,long deadline,long wallDeadline,int savedBoot,int boot,long now,long wall){if(locked)return Phase.PARENT_LOCK;if(!enabled)return Phase.IDLE;if(savedBoot!=boot||now>=deadline||wall>=wallDeadline)return Phase.QUESTION;return Phase.WATCHING;}
 public static boolean grantsReward(String answer,String choice,boolean answered,boolean locked){return !answered&&!locked&&answer!=null&&answer.equals(choice);}
}
