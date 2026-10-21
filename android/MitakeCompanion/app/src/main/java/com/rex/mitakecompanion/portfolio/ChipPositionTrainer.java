package com.rex.mitakecompanion.portfolio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

/** User-imported screenshots train local nav anchors, not an analysis image or an OCR model. */
final class ChipPositionTrainer {
    static String learn(Context context,Uri uri,String code,String page)throws Exception{
        File file=new File(context.getCacheDir(),"learn-"+java.util.UUID.randomUUID()+".jpg");Bitmap image=null;
        try{PortfolioImages.importImage(context,uri,file);image=BitmapFactory.decodeFile(file.getAbsolutePath());if(image==null)throw new IOException("圖片無法讀取");
            ChipPositionMemory memory=new ChipPositionMemory(context);
            try(ChipScreenOcr ocr=new ChipScreenOcr()){ChipScreenOcr.Result scan=ocr.read(image,code,page,memory,false);
                if(!ChipScreenGuide.headerMatches(scan.lines,image.getHeight(),code))throw new IOException("圖片頂部無法核對股號 "+code+"，請提供此股的完整畫面");
                if(!ChipVideoProfile.bodyMatches(scan.text(),page))throw new IOException("圖片內容未確認為「"+page+"」，請選擇對應頁面");
                Rect anchor=ChipScreenGuide.anchor(scan.lines,image.getHeight(),page);if(anchor==null)throw new IOException("未辨識到完整的「"+page+"」頁籤，請提供含頂部頁籤列的畫面");
                memory.confirm(page,anchor,image.getWidth(),image.getHeight(),scan.text(),code);return "已記住「"+page+"」位置。\n"+memory.summary();
            }
        }finally{if(image!=null)image.recycle();file.delete();}
    }
}
