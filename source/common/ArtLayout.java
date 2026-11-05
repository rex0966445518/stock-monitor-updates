package tw.rex.link;
public final class ArtLayout {
 public static float[] fit(float width,float height,float artWidth,float artHeight){if(width<=0||height<=0||artWidth<=0||artHeight<=0)return new float[]{0,0,0};float scale=Math.min(width/artWidth,height/artHeight);return new float[]{scale,(width-artWidth*scale)/2f,(height-artHeight*scale)/2f};}
}
