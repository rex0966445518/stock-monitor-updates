package com.rex.mitakecompanion.portfolio;

import android.graphics.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One recognizer per worker; bounded, sequential zoom crops mapped back to original pixels. */
final class ChipScreenOcr implements AutoCloseable {
    private com.google.android.gms.tasks.Task<Text> inFlight;private boolean warmed;
    private final TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    static final class Result {final List<ChipScreenGuide.Line> lines=new ArrayList<>();final StringBuilder text=new StringBuilder();boolean zoomed,fast;String text(){return text.toString();}}
    Result read(Bitmap image,String code,String page,ChipPositionMemory memory,boolean locate)throws Exception{
        Result result=new Result();int w=image.getWidth(),h=image.getHeight();Rect hint=locate?memory.hint(page,w,h):null;
        if(hint!=null){readRegion(image,new Rect(0,hint.top,w,hint.bottom),2.5f,result);readRegion(image,new Rect(0,0,w,(int)(h*.19)),2,result);boolean label=false;for(ChipScreenGuide.Line line:result.lines)if(ChipVideoProfile.tabLabel(line.text,page))label=true;
            if(label&&ChipScreenGuide.headerMatches(result.lines,h,code)){result.fast=true;memory.count("fast_hits");return result;}memory.miss(page,w,h);result=new Result();}
        readRegion(image,new Rect(0,0,w,h),1,result);memory.count("full_reads");
        if(!ChipScreenGuide.headerMatches(result.lines,h,code))readRegion(image,new Rect(0,0,w,(int)(h*.21)),2,result);
        if(ChipVideoProfile.bodyMatches(result.text(),page))return result;
        ChipScreenGuide.Action visible=ChipScreenGuide.plan(result.lines,w,h,code,page);
        if(visible!=null&&(visible.kind.equals("search")||visible.kind.equals("input")||visible.kind.equals("result")||visible.kind.equals("paste")||locate&&(visible.kind.equals("tab")||visible.kind.equals("swipe"))))return result;
        // Target is not confirmed: enlarge the quote/nav area and body separately.
        readRegion(image,new Rect(0,(int)(h*.14),w,(int)(h*.46)),2.5f,result);
        readRegion(image,new Rect(0,(int)(h*.28),w,(int)(h*.69)),2,result);
        if(!ChipVideoProfile.bodyMatches(result.text(),page))readRegion(image,new Rect(0,(int)(h*.60),w,(int)(h*.96)),1.7f,result);
        result.zoomed=true;memory.count("zoom_reads");return result;
    }
    private void readRegion(Bitmap image,Rect region,float scale,Result result)throws Exception{
        if(inFlight!=null&&!inFlight.isComplete())throw new IllegalStateException("文字辨識器仍在準備，稍後再試");
        if(inFlight!=null&&inFlight.isSuccessful())warmed=true;
        Rect r=new Rect(region);if(!r.intersect(0,0,image.getWidth(),image.getHeight())||r.isEmpty())return;
        Bitmap crop=Bitmap.createBitmap(image,r.left,r.top,r.width(),r.height());if(crop==image)crop=image.copy(Bitmap.Config.ARGB_8888,false);
        float factor=Math.max(.1f,Math.min(scale,Math.min(1800f/r.width(),2200f/r.height())));Bitmap input;
        try{input=factor==1?crop:Bitmap.createScaledBitmap(crop,Math.round(r.width()*factor),Math.round(r.height()*factor),true);}catch(RuntimeException error){crop.recycle();throw error;}
        if(input!=crop)crop.recycle();final Bitmap held=input;com.google.android.gms.tasks.Task<Text> task;
        try{task=recognizer.process(InputImage.fromBitmap(input,0));inFlight=task;}catch(RuntimeException error){input.recycle();throw error;}
        // A timeout does not cancel ML Kit. Its private input must remain alive until completion.
        task.addOnCompleteListener(Runnable::run,completed->held.recycle());
        Text text=Tasks.await(task,warmed?18:60,TimeUnit.SECONDS);warmed=true;result.text.append(text.getText()).append('\n');
        for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){add(result,line.getText(),line.getBoundingBox(),r,factor);for(Text.Element e:line.getElements())add(result,e.getText(),e.getBoundingBox(),r,factor);}
    }
    private static void add(Result result,String text,Rect b,Rect region,float factor){if(b==null)return;Rect mapped=new Rect(region.left+Math.round(b.left/factor),region.top+Math.round(b.top/factor),region.left+Math.round(b.right/factor),region.top+Math.round(b.bottom/factor));result.lines.add(new ChipScreenGuide.Line(text,mapped));}
    @Override public void close(){recognizer.close();}
}
