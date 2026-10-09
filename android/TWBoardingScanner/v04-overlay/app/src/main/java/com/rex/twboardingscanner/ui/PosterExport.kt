package com.rex.twboardingscanner.ui

import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rex.twboardingscanner.domain.RadarType
import com.rex.twboardingscanner.domain.RuleMetrics
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

internal class PosterExport(private val activity:AppCompatActivity) {
    private val executor=Executors.newSingleThreadExecutor()
    private var dialog:androidx.appcompat.app.AlertDialog?=null
    private var files=emptyList<File>()
    private var busy=false
    private val directory=activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) save(uri)
    }
    fun generate(scanning:Boolean, groups:Map<RadarType,List<PosterStock>>, done:Int,total:Int) {
        if(busy) { toast("海報處理中，請稍候");return }
        busy=true;toast("正在製作 A／B／C 三張海報…")
        val now=ZonedDateTime.now(RuleMetrics.TAIPEI);val day=now.toLocalDate().toString()
        val folder=File(activity.cacheDir,"posters/${System.currentTimeMillis()}")
        executor.submit {
            val result=runCatching {
                check(folder.mkdirs())
                RadarType.entries.mapIndexed { index,radar ->
                    File(folder,"台股掃描_${day}_${('A'.code+index).toChar()}.png").also {
                        ScanPoster.write(it,radar,groups.getValue(radar),day,now.format(DateTimeFormatter.ofPattern("HH:mm")),scanning,RadarType.entries.map { groups.getValue(it).size },done,total)
                    }
                }
            }
            activity.runOnUiThread {
                busy=false
                if(activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess {files=it;preview()}.onFailure {folder.deleteRecursively();toast("海報產生失敗：${it.message ?: "請稍後重試"}")}
            }
        }
    }
    private fun preview() {
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12);setBackgroundColor(android.graphics.Color.rgb(4,17,30))}
        val tabs=LinearLayout(activity)
        val image=ImageView(activity).apply {adjustViewBounds=true;setBackgroundColor(android.graphics.Color.rgb(4,17,30))}
        fun show(index:Int){
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(files[index].path,bounds)
            val options=BitmapFactory.Options().apply{inSampleSize=1};while(bounds.outHeight/options.inSampleSize.coerceAtLeast(1)>1600) options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
            image.setImageBitmap(BitmapFactory.decodeFile(files[index].path,options));image.contentDescription="${('A'.code+index).toChar()} 區海報預覽"
        }
        files.forEachIndexed { i,_ -> tabs.addView(Button(activity).apply {text=listOf("A 起漲","B 反轉","C 爆量")[i];setOnClickListener{show(i)}},LinearLayout.LayoutParams(0,(52*activity.resources.displayMetrics.density).toInt(),1f)) }
        root.addView(tabs);root.addView(ScrollView(activity).apply {addView(image)},LinearLayout.LayoutParams(-1,(activity.resources.displayMetrics.heightPixels*.48).toInt()))
        root.addView(TextView(activity).apply {text="預覽已縮小，儲存的是完整解析度 PNG。三張海報可一次儲存或分享。";setTextColor(android.graphics.Color.LTGRAY);textSize=12f})
        dialog=MaterialAlertDialogBuilder(activity).setTitle("今日 A／B／C 海報").setView(root)
            .setPositiveButton("儲存三張",null).setNeutralButton("分享三張",null).setNegativeButton("關閉",null).create()
        dialog?.setOnShowListener {
            dialog?.getButton(-1)?.setOnClickListener {if(Build.VERSION.SDK_INT>=29)save(null)else directory.launch(null)}
            dialog?.getButton(-3)?.setOnClickListener {share()}
        }
        dialog?.show();show(0)
    }
    private fun save(tree:Uri?) {
        if(busy){toast("儲存中，請稍候");return};busy=true
        val batch=files.toList()
        executor.submit {
            val created=mutableListOf<Uri>()
            val result=runCatching {
                val resolver=activity.contentResolver
                batch.forEach { file ->
                    val uri=if(Build.VERSION.SDK_INT>=29 && tree==null) {
                        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME,file.nameWithoutExtension+"_${System.currentTimeMillis()}.png")
                            put(MediaStore.MediaColumns.MIME_TYPE,"image/png");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/TWBoardingScanner")
                            put(MediaStore.MediaColumns.IS_PENDING,1)
                        })
                    } else {
                        requireNotNull(tree);val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                        DocumentsContract.createDocument(resolver,parent,"image/png",file.name)
                    } ?: error("無法建立下載檔案")
                    created.add(uri)
                    (resolver.openOutputStream(uri) ?: error("無法寫入檔案")).use { output -> file.inputStream().use {it.copyTo(output)} }
                }
                if(Build.VERSION.SDK_INT>=29 && tree==null) created.forEach {resolver.update(it,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)}
            }
            if(result.isFailure) created.forEach {runCatching {if(tree==null) activity.contentResolver.delete(it,null,null) else DocumentsContract.deleteDocument(activity.contentResolver,it)}}
            activity.runOnUiThread {busy=false;if(!activity.isDestroyed) toast(if(result.isSuccess) if(tree==null) "三張海報已儲存至 Download/TWBoardingScanner" else "三張海報已儲存至選取資料夾" else "儲存失敗，請重試或使用分享：${result.exceptionOrNull()?.message}")}
        }
    }
    private fun share() {
        runCatching {
            val uris=ArrayList(files.map {FileProvider.getUriForFile(activity,activity.packageName+".posters",it)})
            val intent=Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type="image/png";putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData=ClipData.newUri(activity.contentResolver,"ABC 海報",uris.first()).apply{uris.drop(1).forEach{addItem(ClipData.Item(it))}}
            }
            activity.startActivity(Intent.createChooser(intent,"分享三張掃描海報"))
        }.onFailure {toast("無法開啟分享：${it.message}")}
    }
    private fun toast(message:String)=Toast.makeText(activity,message,Toast.LENGTH_LONG).show()
    fun close(){dialog?.dismiss();executor.shutdownNow()}
}
