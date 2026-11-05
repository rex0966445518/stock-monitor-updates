import tw.rex.link.ArtLayout;
public class ArtLayoutTest {
 public static void main(String[] args){int[][] sizes={{1920,1080},{1280,720},{900,85},{220,120},{320,180}};for(int[] size:sizes){float[] f=ArtLayout.fit(size[0],size[1],900,190);if(f[0]<=0||f[1]<-.001||f[2]<-.001||900*f[0]>size[0]+.001||190*f[0]>size[1]+.001)throw new AssertionError();float width=900*f[0],height=190*f[0];if(Math.abs(width/height-900f/190f)>.0001)throw new AssertionError("distorted");System.out.println("PASS proportional artwork fits "+size[0]+"x"+size[1]);}}
}
