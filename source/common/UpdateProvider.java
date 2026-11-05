package tw.rex.link;
import android.content.*;import android.database.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import java.io.*;
public class UpdateProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 File file(Uri uri)throws FileNotFoundException{if(!"/package.apk".equals(uri.getPath()))throw new FileNotFoundException();return new File(getContext().getCacheDir(),"updates/package.apk");}
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!mode.equals("r"))throw new FileNotFoundException();return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
 public String getType(Uri uri){return "application/vnd.android.package-archive";}
 public Cursor query(Uri u,String[] projection,String selection,String[] args,String sort){try{String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];File f=file(u);for(int i=0;i<columns.length;i++)row[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?"update.apk":columns[i].equals(OpenableColumns.SIZE)?f.length():null;cursor.addRow(row);return cursor;}catch(Exception e){return null;}}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
