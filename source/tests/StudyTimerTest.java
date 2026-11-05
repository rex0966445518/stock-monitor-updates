import tw.rex.link.StudyTimer;
public class StudyTimerTest {
 static void equal(long want,long got){if(want!=got)throw new AssertionError(want+" != "+got);}
 public static void main(String[] args){StudyTimer t=new StudyTimer();t.start(100);equal(250,t.elapsed(350));t.pause(350);equal(250,t.elapsed(900000));t.pause(4000000);equal(250,t.elapsed(4000001));t.resume(5000000);equal(350,t.elapsed(5000100));t.resume(5000200);equal(450,t.elapsed(5000200));t.reset(6000000);equal(0,t.elapsed(6000000));equal(100,t.elapsed(6000100));t.pause(6000100);t.resume(7000000);equal(120,t.elapsed(7000020));System.out.println("PASS active time, repeated pause/resume, background exclusion, next-attempt reset");}
}
