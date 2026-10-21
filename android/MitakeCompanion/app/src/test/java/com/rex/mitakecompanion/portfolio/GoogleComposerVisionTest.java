package com.rex.mitakecompanion.portfolio;
import org.junit.Test;
import static org.junit.Assert.*;
public class GoogleComposerVisionTest {
    private static void row(int[] pixels,int w,int h,int cy,boolean plus,boolean arrow){
        int px=Math.round(w*.123f),sx=Math.round(w*.875f),rx=Math.round(w*.062f),ry=Math.round(w*.046f),arm=Math.round(w*.019f),stroke=Math.max(2,Math.round(w*.003f));
        for(int y=Math.max(0,cy-ry);y<Math.min(h,cy+ry+1);y++)for(int x=Math.max(0,px-rx);x<Math.min(w,sx+rx+1);x++){
            int dx=x-sx,dy=y-cy;
            if((float)dx*dx/(rx*rx)+(float)dy*dy/(ry*ry)<=1){pixels[y*w+x]=0xff3b70f0;if(arrow&&((Math.abs(dx)<=stroke&&Math.abs(dy)<=arm)||(Math.abs(dy-(Math.abs(dx)-arm))<=stroke&&Math.abs(dx)<=arm)))pixels[y*w+x]=0xfff4f8ff;}
            dx=x-px;if((float)dx*dx/(rx*rx)+(float)dy*dy/(ry*ry)<=1){pixels[y*w+x]=0xff50535a;if(plus&&((Math.abs(dx)<=stroke&&Math.abs(dy)<=arm)||(Math.abs(dy)<=stroke&&Math.abs(dx)<=arm)))pixels[y*w+x]=0xffe4e7ee;}
        }
    }
    @Test public void recognizesKeyboardAndExpandedComposerAtDifferentScales(){for(int w:new int[]{360,691,900,1080})for(float position:new float[]{.56f,.93f}){int h=Math.round(w*2.22f),y=Math.round(h*position);int[] p=new int[w*h];row(p,w,h,y,true,true);int[] found=GoogleComposerVision.find(p,w,h);assertNotNull(w+" @ "+position,found);assertEquals(w*.123f,found[0],2);assertEquals(y,found[1],2);}}
    @Test public void blueButtonAloneDoesNotCauseBlindTap(){int[] p=new int[720*1600];row(p,720,1600,1450,false,true);assertNull(GoogleComposerVision.find(p,720,1600));}
    @Test public void unrelatedBlueButtonMustContainUpArrow(){int[] p=new int[720*1600];row(p,720,1600,1450,true,false);assertNull(GoogleComposerVision.find(p,720,1600));}
    @Test public void multipleComposerRowsAreRejected(){int[] p=new int[720*1600];row(p,720,1600,900,true,true);row(p,720,1600,1450,true,true);assertNull(GoogleComposerVision.find(p,720,1600));}
    @Test public void addressBarPlusDoesNotBecomeAttachment(){int[] p=new int[720*1600];row(p,720,1600,120,true,true);assertNull(GoogleComposerVision.find(p,720,1600));}
    @Test public void malformedOrEmptyImageCannotBecomeTap(){assertNull(GoogleComposerVision.find(new int[0],720,1600));assertNull(GoogleComposerVision.find(new int[720*1600],720,1600));}
}
