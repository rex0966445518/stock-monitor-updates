package tw.rex.nextmarket;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.widget.Toast;
public class InstallResult extends BroadcastReceiver {
 @Override public void onReceive(Context context, Intent result){
  int status=result.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
  if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent confirm=result.getParcelableExtra(Intent.EXTRA_INTENT);if(confirm!=null)context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
  else Toast.makeText(context,status==PackageInstaller.STATUS_SUCCESS?"更新完成":"更新未完成："+result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),Toast.LENGTH_LONG).show();
 }
}
