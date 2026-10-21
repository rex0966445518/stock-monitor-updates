package com.rex.mitakecompanion.portfolio;

import android.content.*;
import android.database.*;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import org.json.*;

/** A short-lived read-only picker root exposing exactly this run's six validated images. */
public final class CaptureDocuments extends DocumentsProvider {
    static final String ROOT="漲停雷達六頁圖片";
    static void offer(Context c,String id)throws Exception{JSONObject r=PortfolioStore.get(c).run(id);if(r.getJSONObject("pages").length()!=6)throw new IOException("六頁未完成");c.getSharedPreferences("capture_upload",0).edit().clear().putString("run",id).putLong("until",System.currentTimeMillis()+15*60_000).apply();notifyRoots(c);}
    static void close(Context c){c.getSharedPreferences("capture_upload",0).edit().clear().apply();notifyRoots(c);}
    private static void notifyRoots(Context c){c.getContentResolver().notifyChange(DocumentsContract.buildRootsUri(c.getPackageName()+".capture-documents"),null);}
    @Override public boolean onCreate(){return true;}
    String active()throws FileNotFoundException{SharedPreferences p=getContext().getSharedPreferences("capture_upload",0);String id=p.getString("run","");if(id.isEmpty()||p.getLong("until",0)<System.currentTimeMillis())throw new FileNotFoundException("本次圖片選取已結束");return id;}
    static String name(int i){return (i+1)+"-"+PortfolioStore.PAGES[i]+".jpg";}
    private File image(String doc)throws FileNotFoundException{try{String id=active();String[] parts=doc.split(":",-1);if(parts.length!=2||!parts[0].equals(id))throw new IOException();int i=Integer.parseInt(parts[1]);if(i<0||i>=6)throw new IOException();PortfolioStore store=PortfolioStore.get(getContext());File f=store.imageFile(id,PortfolioStore.PAGES[i]);JSONObject item=store.run(id).getJSONObject("pages").getJSONObject(PortfolioStore.PAGES[i]);if(!f.isFile()||!f.getCanonicalPath().equals(new File(item.getString("path")).getCanonicalPath()))throw new IOException();return f;}catch(Exception e){throw new FileNotFoundException("不是本次六頁圖片");}}
    @Override public Cursor queryRoots(String[] projection){String[] columns=projection!=null?projection:new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_MIME_TYPES,DocumentsContract.Root.COLUMN_SUMMARY};MatrixCursor rows=new MatrixCursor(columns);try{String id=active();MatrixCursor.RowBuilder row=rows.newRow();for(String c:columns){Object v=null;switch(c){case DocumentsContract.Root.COLUMN_ROOT_ID:v="capture";break;case DocumentsContract.Root.COLUMN_DOCUMENT_ID:v=id;break;case DocumentsContract.Root.COLUMN_TITLE:v=ROOT;break;case DocumentsContract.Root.COLUMN_FLAGS:v=DocumentsContract.Root.FLAG_LOCAL_ONLY;break;case DocumentsContract.Root.COLUMN_MIME_TYPES:v="image/jpeg";break;case DocumentsContract.Root.COLUMN_SUMMARY:v="僅本次六張，15分鐘後關閉";break;}row.add(v);}}catch(Exception ignored){}return rows;}
    @Override public Cursor queryDocument(String doc,String[] projection)throws FileNotFoundException{MatrixCursor rows=cursor(projection);if(doc.equals(active()))add(rows,doc,ROOT,null);else{File f=image(doc);add(rows,doc,name(Integer.parseInt(doc.split(":")[1])),f);}return rows;}
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort)throws FileNotFoundException{if(!parent.equals(active()))throw new FileNotFoundException();MatrixCursor rows=cursor(projection);for(int i=0;i<6;i++){String doc=parent+":"+i;add(rows,doc,name(i),image(doc));}return rows;}
    private MatrixCursor cursor(String[] projection){return new MatrixCursor(projection!=null?projection:new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED});}
    private void add(MatrixCursor rows,String id,String title,File f){MatrixCursor.RowBuilder row=rows.newRow();for(String c:rows.getColumnNames()){Object v=null;switch(c){case DocumentsContract.Document.COLUMN_DOCUMENT_ID:v=id;break;case DocumentsContract.Document.COLUMN_DISPLAY_NAME:v=title;break;case DocumentsContract.Document.COLUMN_MIME_TYPE:v=f==null?DocumentsContract.Document.MIME_TYPE_DIR:"image/jpeg";break;case DocumentsContract.Document.COLUMN_FLAGS:v=0;break;case DocumentsContract.Document.COLUMN_SIZE:v=f==null?0:f.length();break;case DocumentsContract.Document.COLUMN_LAST_MODIFIED:v=f==null?0:f.lastModified();break;}row.add(v);}}
    static int chromeReads(Context c,String id){SharedPreferences p=c.getSharedPreferences("capture_upload",0);if(!id.equals(p.getString("run",""))||p.getLong("until",0)<System.currentTimeMillis())return 0;return Integer.bitCount(p.getInt("chrome_reads",0)&63);}
    @Override public ParcelFileDescriptor openDocument(String doc,String mode,CancellationSignal signal)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("僅可讀取");File f=image(doc);ParcelFileDescriptor fd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
        if(GoogleSearchCapture.CHROME.equals(getCallingPackage())){SharedPreferences p=getContext().getSharedPreferences("capture_upload",0);int index=Integer.parseInt(doc.split(":")[1]);synchronized(CaptureDocuments.class){p.edit().putInt("chrome_reads",p.getInt("chrome_reads",0)|(1<<index)).commit();}}return fd;}
    @Override public boolean isChildDocument(String parent,String doc){try{return parent.equals(active())&&doc.startsWith(parent+":")&&image(doc).isFile();}catch(Exception e){return false;}}
}
