package com.rex.mitakecompanion.portfolio;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import org.json.*;

/** Synthetic screens only: exercises real bundled Chinese OCR, zoom mapping and resume on Android. */
public final class CaptureLearningInstrumentation extends Instrumentation {
    private static int ocrRetries;
    private static final String[] BODIES={"日分價量 還原日\n成交量\nMACD DIF 3.55", "主力動向 大買\n籌碼集中 1403張\n成交量 4609張", "三大法人買賣超\n外資 投信 自營商", "多方優勢\n多方 符合33項\n籌碼面 價量面", "主力買賣超\n買賣家數差\n5日集中 20日集中", "大戶持股\n散戶持股\n張數比例 大戶增減"};
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Bitmap screen(int index,int navSize){Bitmap image=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(32,32,32));Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);p.setTextSize(34);canvas.drawText("宏正 6277",250,110,p);p.setTextSize(navSize);int[] x={12,120,300,400,500,600};for(int i=0;i<6;i++)canvas.drawText(PortfolioStore.PAGES[i],x[i],410,p);p.setTextSize(34);int y=530;for(String row:BODIES[index].split("\n")){canvas.drawText(row,28,y,p);y+=90;}return image;}
    private static void composerIcons(){
        for(int y:new int[]{900,1450}){Bitmap image=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);try{Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(32,33,36));Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(75,80,88));canvas.drawOval(45,y-34,131,y+34,paint);paint.setColor(Color.rgb(60,110,240));canvas.drawOval(587,y-34,673,y+34,paint);paint.setColor(Color.rgb(239,244,255));paint.setStrokeWidth(4);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawLine(74,y,102,y,paint);canvas.drawLine(88,y-14,88,y+14,paint);canvas.drawLine(630,y-14,630,y+14,paint);canvas.drawLine(616,y,630,y-14,paint);canvas.drawLine(630,y-14,644,y,paint);int[] pixels=new int[720*1600];image.getPixels(pixels,0,720,0,0,720,1600);int[] pair=GoogleComposerVision.find(pixels,720,1600);require(pair!=null&&Math.abs(pair[0]-88)<3&&Math.abs(pair[1]-y)<3,"未辨識原生點陣圖中的Google附件圖示");}finally{image.recycle();}}
    }
    private static ChipScreenOcr.Result readFixture(ChipScreenOcr ocr,Bitmap image,String page,ChipPositionMemory memory,boolean locate)throws Exception{
        long until=android.os.SystemClock.elapsedRealtime()+120000;
        while(true){try{return ocr.read(image,"6277",page,memory,locate);}catch(java.util.concurrent.TimeoutException e){if(android.os.SystemClock.elapsedRealtime()>=until)throw e;ocrRetries++;System.out.println("OCR fixture timed out; preserving its pending input and retrying within 120 seconds");}catch(IllegalStateException e){if(!String.valueOf(e.getMessage()).contains("仍在準備")||android.os.SystemClock.elapsedRealtime()>=until)throw e;}
            Thread.sleep(500);
        }
    }
    @Override public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();File file=new File(c.getCacheDir(),"native-learning-fixture.jpg");ChipPositionMemory memory=new ChipPositionMemory(c);memory.clear();
        try(ChipScreenOcr ocr=new ChipScreenOcr()){
            composerIcons();JSONObject pages=new JSONObject();int captured=0;
            for(int i=0;i<6;i++){Bitmap image=screen(i,26);try{
                ChipScreenOcr.Result scan=readFixture(ocr,image,PortfolioStore.PAGES[i],memory,false);
                ChipScreenGuide.Action action=ChipScreenGuide.plan(scan.lines,720,1600,"6277",PortfolioStore.PAGES[i]);require(action!=null&&action.kind.equals("capture"),"未辨識第"+i+"頁："+scan.text());
                pages.put(PortfolioStore.PAGES[i],new JSONObject());require(ChipRecovery.nextMissing(new JSONObject().put("pages",pages))==i+1,"續傳未前進至下一缺頁");captured++;
                Rect anchor=ChipScreenGuide.anchor(scan.lines,1600,PortfolioStore.PAGES[i]);require(anchor!=null,"頁籤未定位 "+PortfolioStore.PAGES[i]);memory.confirm(PortfolioStore.PAGES[i],anchor,720,1600,scan.text(),"6277");memory.confirm(PortfolioStore.PAGES[i],anchor,720,1600,scan.text(),"6277");
            }finally{image.recycle();}}
            Bitmap previous=screen(0,16);boolean zoomed,fast;
            try{ChipScreenOcr.Result recovery=readFixture(ocr,previous,"籌碼日報",memory,false);zoomed=recovery.zoomed;require(zoomed,"未執行放大辨識");Rect anchor=ChipScreenGuide.anchor(recovery.lines,1600,"籌碼日報");require(anchor!=null&&anchor.top>350&&anchor.bottom<450,"放大座標未還原至原圖 "+anchor);
                require(!ChipVideoProfile.bodyMatches(recovery.text(),"籌碼日報"),"把上一張K線誤當籌碼日報");
                ChipScreenOcr.Result hit=readFixture(ocr,previous,"籌碼日報",memory,true);fast=hit.fast;require(fast,"已學習位置未命中");ChipScreenGuide.Action action=ChipScreenGuide.plan(hit.lines,720,1600,"6277","籌碼日報");require(action!=null&&action.kind.equals("tab"),"快速辨識未定位籌碼日報按鈕");
            }finally{previous.recycle();}
            Bitmap learn=screen(1,26);try{PortfolioImages.write(learn,file);}finally{learn.recycle();}
            String learned=ChipPositionTrainer.learn(c,Uri.fromFile(file),"6277","籌碼日報");require(learned.contains("已記住"),"匯入學習失敗");
            result.putString("capture_learning",new JSONObject().put("ok",true).put("synthetic",true).put("real_chipk_test",false).put("ocr_fixture_retries",ocrRetries).put("google_composer_icons",true).put("real_google_upload",false).put("six_page_ocr",captured).put("enlarged_ocr",zoomed).put("coordinate_mapping",true).put("learned_fast_hit",fast).put("imported_learning",true).put("resume_to_next_missing",true).toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){StringWriter out=new StringWriter();error.printStackTrace(new PrintWriter(out));result.putString("stream",out.toString());finish(0,result);}finally{file.delete();memory.clear();}
    }
}
