package com.rex.mitakecompanion.portfolio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.*;
import java.util.concurrent.TimeUnit;

final class PortfolioImages {
    static File importImage(Context context,Uri uri,File target)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
        if(bounds.outWidth<32||bounds.outHeight<32||bounds.outWidth>30000||bounds.outHeight>30000)throw new IOException("圖片尺寸無效，請使用原始手機截圖");
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/opts.inSampleSize>2400)opts.inSampleSize*=2;
        Bitmap bitmap;try(InputStream in=context.getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,opts);}
        if(bitmap==null)throw new IOException("無法讀取這張圖片");
        try{write(bitmap,target);}finally{bitmap.recycle();}return target;
    }
    static void write(Bitmap bitmap,File target)throws Exception{
        File temp=new File(target.getParentFile(),target.getName()+".tmp");
        try{
            try(FileOutputStream out=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("圖片編碼失敗");out.getFD().sync();}
            if(temp.length()>3_000_000)throw new IOException("截圖過大，請裁切後重試");
            if(!temp.renameTo(target))throw new IOException("圖片儲存失敗");
        }finally{if(temp.exists())temp.delete();}
    }
    static Text recognize(File file)throws Exception{
        Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());if(bitmap==null)throw new IOException("圖片無法讀取");
        TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try{return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS);}finally{recognizer.close();bitmap.recycle();}
    }
}
