package tw.radar.shortscan;
import android.content.*;import android.database.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import java.io.*;
public class UpdateProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 private File file(Uri u){if(!"/radar-update.apk".equals(u.getPath()))throw new SecurityException("Invalid path");return new File(getContext().getCacheDir(),"radar-update.apk");}
 @Override public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new SecurityException("Read only");return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);}
 @Override public String getType(Uri u){file(u);return "application/vnd.android.package-archive";}
 @Override public Cursor query(Uri u,String[] projection,String selection,String[] args,String sort){File f=file(u);String[] p=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(p);Object[] row=new Object[p.length];for(int i=0;i<p.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(p[i])?f.getName():OpenableColumns.SIZE.equals(p[i])?f.length():null;c.addRow(row);return c;}
 @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}