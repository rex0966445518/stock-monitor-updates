package com.rex.mitakecompanion.portfolio;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.zip.*;

/** One complete, labelled six-page group; the Gemini request uses the same six JPEGs. */
public final class AnalysisBundle {
    private AnalysisBundle(){}
    public static File create(PortfolioStore store,String id)throws Exception{
        JSONObject run=store.run(id),pages=run.getJSONObject("pages");
        if(pages.length()!=6)throw new IllegalStateException("六頁圖片尚未完整，未打包");
        File destination=new File(store.imageFile(id,PortfolioStore.PAGES[0]).getParentFile(),"six-pages.zip");
        File temporary=File.createTempFile("bundle-",".tmp",destination.getParentFile());
        JSONArray manifestPages=new JSONArray();long total=0;
        try{
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(temporary))){
                for(int i=0;i<PortfolioStore.PAGES.length;i++){
                    String page=PortfolioStore.PAGES[i];JSONObject image=pages.getJSONObject(page);File file=store.imageFile(id,page);
                    if(!new File(image.getString("path")).getCanonicalFile().equals(file.getCanonicalFile())||!file.isFile()||file.length()==0||file.length()>3_000_000||(total+=file.length())>12_000_000)throw new IOException("圖片遺失或超過大小限制，未打包");
                    byte[] bytes=Files.readAllBytes(file.toPath());String entry=(i+1)+"-"+page+".jpg";
                    zip.putNextEntry(new ZipEntry(entry));zip.write(bytes);zip.closeEntry();
                    StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.US,"%02x",b&255));
                    manifestPages.put(new JSONObject().put("page",page).put("file",entry).put("sha256",hash.toString()).put("saved",image.optLong("saved")).put("source",image.optString("source")));
                }
                JSONObject manifest=new JSONObject().put("run",id).put("target",run.getJSONObject("holding")).put("source",run.optString("source","portfolio")).put("created",run.getLong("created")).put("note","儲存時間不是行情日期；請核對各圖資料日期與週期").put("pages",manifestPages);
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            synchronized(store){JSONObject latest=store.run(id);if(!pages.toString().equals(latest.getJSONObject("pages").toString()))throw new IOException("打包期間圖片已變更，請重新打包");
                Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);latest.put("bundle",destination.getAbsolutePath()).put("bundled",System.currentTimeMillis());store.updateRun(latest);}
            return destination;
        }finally{temporary.delete();}
    }
}
