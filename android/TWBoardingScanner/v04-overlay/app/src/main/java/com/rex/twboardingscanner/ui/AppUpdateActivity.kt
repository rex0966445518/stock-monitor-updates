package com.rex.twboardingscanner.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager
import com.rex.twboardingscanner.update.*
import java.util.Locale

class AppUpdateActivity:AppCompatActivity(){
    private lateinit var store:AppUpdateStore
    private lateinit var headline:TextView;private lateinit var version:TextView;private lateinit var detail:TextView;private lateinit var notes:TextView
    private lateinit var bar:ProgressBar;private lateinit var primary:com.google.android.material.button.MaterialButton;private lateinit var pause:com.google.android.material.button.MaterialButton
    private val handler=Handler(Looper.getMainLooper())
    private var autoInstall=false;private var awaitingPermission=false;private var verifying=false;private var visible=false
    private val polling=object:Runnable{override fun run(){render();handler.postDelayed(this,700)}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);store=AppUpdateStore(this)
        autoInstall=savedInstanceState?.getBoolean("autoInstall")?:false;awaitingPermission=savedInstanceState?.getBoolean("awaitingPermission")?:false
        val root=NeonUi.vertical(this).apply{setPadding(NeonUi.dp(this@AppUpdateActivity,16),NeonUi.dp(this@AppUpdateActivity,16),NeonUi.dp(this@AppUpdateActivity,16),NeonUi.dp(this@AppUpdateActivity,28))}
        val scroll=ScrollView(this).apply{setBackgroundColor(NeonUi.canvas);addView(root)};setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->val b=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);insets}
        root.addView(NeonUi.header(this,"軟件更新","SYSTEM UPDATE"){finish()})
        root.addView(NeonUi.label(this,"GitHub 官方更新包 · 支援跨版本更新",12f,NeonUi.cyan));root.addView(NeonUi.gap(this,16))
        val panel=NeonUi.vertical(this).apply{background=NeonUi.panel(this@AppUpdateActivity,NeonUi.cyan);setPadding(NeonUi.dp(this@AppUpdateActivity,16),NeonUi.dp(this@AppUpdateActivity,16),NeonUi.dp(this@AppUpdateActivity,16),NeonUi.dp(this@AppUpdateActivity,16))}
        headline=NeonUi.label(this,"準備檢查更新",22f,NeonUi.ink,true);panel.addView(headline)
        version=NeonUi.label(this,"",14f,NeonUi.cyan);panel.addView(version);panel.addView(NeonUi.gap(this,16))
        bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progressTintList=android.content.res.ColorStateList.valueOf(NeonUi.cyan);progressBackgroundTintList=android.content.res.ColorStateList.valueOf(NeonUi.blue)}
        panel.addView(bar,android.widget.LinearLayout.LayoutParams(-1,NeonUi.dp(this,8)));panel.addView(NeonUi.gap(this,10))
        detail=NeonUi.label(this,"",13f);panel.addView(detail);root.addView(panel);root.addView(NeonUi.gap(this,12))
        primary=NeonUi.primary(this,"一鍵更新"){if(store.snapshot().optString("status")=="READY"&&isNewer())install() else startUpdate()}.apply{tag="update-primary"};root.addView(primary)
        pause=NeonUi.button(this,"暫停下載",NeonUi.amber){autoInstall=false;store.cancel();WorkManager.getInstance(this).cancelUniqueWork(AppUpdateWorker.WORK);render()}.apply{tag="update-pause"};root.addView(pause)
        root.addView(NeonUi.label(this,"下載中斷可重試接續；更新後保留掃描設定與回測日誌。安裝需由 Android 確認，首次可能要求允許此 App 安裝更新。",12f))
        root.addView(NeonUi.gap(this,16));val releaseNotes=NeonUi.section(this,"版本更新說明");root.addView(releaseNotes)
        notes=NeonUi.label(this,"按一鍵更新取得最新版說明。",13f);notes.setTextIsSelectable(true);releaseNotes.addView(notes)
        root.addView(NeonUi.gap(this,16));root.addView(NeonUi.button(this,"開啟 GitHub 發布頁"){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdateSource.PAGE)))}.onFailure{Toast.makeText(this,"無法開啟瀏覽器",Toast.LENGTH_SHORT).show()}})
        render()
        if(savedInstanceState==null&&intent.getBooleanExtra("startUpdate",false)){
            if(store.snapshot().optString("status") in AppUpdateStore.busy)autoInstall=true
            else startUpdate()
        }
    }
    override fun onResume(){super.onResume();visible=true;handler.post(polling)
        if(awaitingPermission){awaitingPermission=false;if(packageManager.canRequestPackageInstalls())install()else Toast.makeText(this,"尚未允許安裝，請再次按安裝更新",Toast.LENGTH_LONG).show()}
    }
    override fun onPause(){visible=false;handler.removeCallbacks(polling);super.onPause()}
    override fun onSaveInstanceState(outState:Bundle){outState.putBoolean("autoInstall",autoInstall);outState.putBoolean("awaitingPermission",awaitingPermission);super.onSaveInstanceState(outState)}
    private fun isNewer()=(store.spec()?.versionCode?:0)>AppUpdateInstaller.code(AppUpdateInstaller.installed(this))
    private fun startUpdate(){
        autoInstall=true
        runCatching{AppUpdateWorker.start(this)}.onFailure{autoInstall=false;Toast.makeText(this,"無法開始更新，請重試",Toast.LENGTH_LONG).show()}
        render()
    }
    internal fun render(){
        val state=store.snapshot();val s=store.spec();val status=state.optString("status")
        val installed=AppUpdateInstaller.installed(this);val newer=s!=null&&s.versionCode>AppUpdateInstaller.code(installed)
        val ready=status=="READY"&&newer;val current=status=="CURRENT"||(status=="READY"&&!newer)
        val busy=status in AppUpdateStore.busy
        headline.text=when{ready->"更新包準備完成";current->"已是最新版本";status=="ERROR"->"更新未完成";status=="PAUSED"->"已暫停更新";status=="DOWNLOADING"->"正在下載更新";status=="VERIFYING"->"正在驗證更新包";busy->"檢查最新版…";else->"一鍵更新"}
        version.text="目前 v${installed.versionName}"+(s?.let{"  →  v${it.versionName}"}?:"")
        bar.isIndeterminate=status in setOf("CHECKING","QUEUED","VERIFYING")
        val bytes=state.optLong("bytes",0).coerceAtLeast(0);val total=s?.size?:0
        val pct=if(total>0)(bytes*100/total).toInt().coerceIn(0,100)else 0;bar.progress=if(current)100 else pct
        detail.text=if(current)"無需更新，可再次檢查最新發布。" else state.optString("message","從 GitHub 檢查、下載並安裝最新版。")+
            if(total>0&&!current)"\n${String.format(Locale.US,"%.2f / %.2f MB",bytes/1048576.0,total/1048576.0)} · $pct%" else ""
        primary.isEnabled=!busy&&!verifying
        primary.text=when{verifying->"驗證中…";ready->"安裝更新";busy->"更新進行中…";current->"重新檢查更新";status in setOf("ERROR","PAUSED")->"重試／接續更新";else->"一鍵更新"}
        pause.visibility=if(busy)View.VISIBLE else View.GONE
        notes.text=s?.let{"v${it.versionName} · ${it.publishedAt}\n\n${it.notes.ifBlank{"本版未提供更新說明"}}"}?:"按一鍵更新取得最新版說明。"
        if(ready&&autoInstall&&visible&&!verifying){autoInstall=false;install()}
    }
    private fun install(){
        if(verifying)return
        val spec=store.spec()?:return;val file=store.apk(spec)
        verifying=true;autoInstall=false;render()
        Thread{
            val result=runCatching{AppUpdateInstaller.verify(this,file,spec)}
            runOnUiThread{
                if(isFinishing||isDestroyed)return@runOnUiThread
                verifying=false;render()
                result.onSuccess{
                    if(!visible)return@onSuccess
                    runCatching{
                        if(!packageManager.canRequestPackageInstalls()){
                            awaitingPermission=true
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))
                        }else startActivity(AppUpdateInstaller.intent(this,file))
                    }.onFailure{awaitingPermission=false;Toast.makeText(this,"無法開啟安裝畫面，請使用 GitHub 發布頁下載",Toast.LENGTH_LONG).show()}
                }.onFailure{Toast.makeText(this,it.message?:"安裝包驗證失敗",Toast.LENGTH_LONG).show();store.cancel();render()}
            }
        }.start()
    }
}
