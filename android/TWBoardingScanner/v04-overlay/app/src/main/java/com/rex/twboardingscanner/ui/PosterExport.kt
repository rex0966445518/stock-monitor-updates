package com.rex.twboardingscanner.ui

import android.content.*
import android.graphics.Bitmap
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

/**
 * Generate ABCD from a single live snapshot. Keep the generated file list,
 * preview tabs, batch save and sharing aligned with the SAME poster catalog.
 */
internal class PosterExport(private val activity:AppCompatActivity) {
    private val executor=Executors.newSingleThreadExecutor()
    private var dialog:androidx.appcompat.app.AlertDialog?=null
    private var previewBitmap:Bitmap?=null
    private var files=emptyList<File>()
    private var busy=false
    private val directory=activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null)save(uri)
    }

    fun generate(scanning:Boolean,groups:Map<RadarType,List<PosterStock>>,done:Int,total:Int){
        if(busy){toast("海報處理中，請稍候");return}
        dialog?.dismiss() // Release any former preview before creating a new snapshot.
        busy=true
        toast("正在製作 ${PosterCatalog.summary} 共 ${PosterCatalog.count} 張海報…")
        val now=ZonedDateTime.now(RuleMetrics.TAIPEI)
        val day=now.toLocalDate().toString()
        val folder=File(activity.cacheDir,"posters/${System.currentTimeMillis()}")
        executor.submit{
            val result=runCatching{
                require(folder.mkdirs()||folder.isDirectory){"無法建立海報暫存資料夾"}
                val counts=PosterCatalog.sections.map{groups[it.radar].orEmpty().size}
                PosterCatalog.sections.map{section->
                    File(folder,PosterCatalog.fileName(day,section)).also{file->
                        ScanPoster.write(file,section.radar,groups[section.radar].orEmpty(),day,
                            now.format(DateTimeFormatter.ofPattern("HH:mm")),
                            scanning,counts,done,total)
                        check(file.isFile&&file.length()>0){"${section.label} 區海報檔案未建立"}
                    }
                }
            }
            activity.runOnUiThread{
                busy=false
                if(activity.isFinishing||activity.isDestroyed){folder.deleteRecursively();return@runOnUiThread}
                result.fold({produced->
                    files=produced
                    runCatching{preview()}.onFailure{err->
                        toast("預覽無法開啟：${err.message?:"圖片解碼失敗"}。原圖已產生，可重試匯出。")
                    }
                },{error->
                    folder.deleteRecursively()
                    toast("海報產生失敗：${error.message?:"請稍後重試"}")
                })
            }
        }
    }

    /** Never decode a poster's entire height at original resolution in the UI. */
    private fun decodePreview(file:File):Bitmap {
        require(file.isFile&&file.length()>0){"海報檔案遺失，請重新產生"}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0){"海報 PNG 格式無法讀取"}
        val options=BitmapFactory.Options().apply{
            inSampleSize=PosterCatalog.previewSample(bounds.outWidth,bounds.outHeight)
            inPreferredConfig=Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.path,options)
            ?:error("裝置無法載入海報預覽；請確認剩餘記憶體")
    }

    private fun preview(){
        require(files.size==PosterCatalog.count&&files.all{it.isFile&&it.length()>0}){
            "海報數量或檔案不完整，請重新匯出"
        }
        val root=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(20,12,20,12)
            setBackgroundColor(android.graphics.Color.rgb(4,17,30))
        }
        val image=ImageView(activity).apply{
            tag="poster-preview-image"
            adjustViewBounds=true
            setBackgroundColor(android.graphics.Color.rgb(4,17,30))
            contentDescription="掃描海報預覽"
        }
        fun show(index:Int){
            val section=PosterCatalog.sections.getOrNull(index)?:error("找不到海報分類")
            val file=files.getOrNull(index)?:error("該分類的海報檔案不存在")
            val next=decodePreview(file)
            val former=previewBitmap
            image.setImageBitmap(next)
            image.contentDescription="${section.label} 海報預覽"
            previewBitmap=next
            if(former!=null&&former!==next&&!former.isRecycled)former.recycle()
        }
        val tabs=PosterCatalog.tabs(activity){index->
            runCatching{show(index)}.onFailure{toast("無法顯示預覽：${it.message}")}
        }
        root.addView(tabs)
        root.addView(ScrollView(activity).apply{addView(image)},
            LinearLayout.LayoutParams(-1,(activity.resources.displayMetrics.heightPixels*.48).toInt()))
        root.addView(TextView(activity).apply{
            text="預覽已縮小；儲存與分享均為 ${PosterCatalog.count} 張完整解析度 PNG（含 D 主力進場）。"
            setTextColor(android.graphics.Color.LTGRAY)
            textSize=12f
        })
        dialog=MaterialAlertDialogBuilder(activity).setTitle("${PosterCatalog.summary} 海報（共 ${PosterCatalog.count} 張）")
            .setView(root)
            .setPositiveButton("儲存全部 ${PosterCatalog.count} 張",null)
            .setNeutralButton("分享全部 ${PosterCatalog.count} 張",null)
            .setNegativeButton("關閉",null)
            .create().also{panel->
                panel.setOnShowListener{
                    panel.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener{
                        if(Build.VERSION.SDK_INT>=29)save(null)else directory.launch(null)
                    }
                    panel.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener{share()}
                }
                panel.setOnDismissListener{
                    image.setImageDrawable(null)
                    previewBitmap?.recycle()
                    previewBitmap=null
                    if(dialog===panel)dialog=null
                }
            }
        dialog?.show()
        runCatching{show(0)}.onFailure{
            toast("海報預覽載入失敗：${it.message}")
            dialog?.dismiss()
        }
    }

    private fun save(tree:Uri?){
        if(busy){toast("儲存中，請稍候");return}
        val batch=files.toList()
        if(batch.size!=PosterCatalog.count||batch.any{!it.isFile}){
            toast("海報檔案不完整，請重新匯出");return
        }
        busy=true
        executor.submit{
            val created=mutableListOf<Uri>()
            val result=runCatching{
                val resolver=activity.contentResolver
                batch.forEach{file->
                    val uri=if(Build.VERSION.SDK_INT>=29&&tree==null){
                        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{
                            put(MediaStore.MediaColumns.DISPLAY_NAME,file.nameWithoutExtension+"_${System.currentTimeMillis()}.png")
                            put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/TWBoardingScanner")
                            put(MediaStore.MediaColumns.IS_PENDING,1)
                        })
                    }else{
                        requireNotNull(tree){"請先選擇儲存資料夾"}
                        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,
                            DocumentsContract.getTreeDocumentId(tree))
                        DocumentsContract.createDocument(resolver,parent,"image/png",file.name)
                    }?:error("無法建立下載檔案")
                    created+=uri
                    (resolver.openOutputStream(uri)?:error("無法寫入下載檔案")).use{out->
                        file.inputStream().use{it.copyTo(out)}
                    }
                }
                if(Build.VERSION.SDK_INT>=29&&tree==null)
                    created.forEach{resolver.update(it,ContentValues().apply{
                        put(MediaStore.MediaColumns.IS_PENDING,0)
                    },null,null)}
            }
            if(result.isFailure)created.forEach{uri->
                runCatching{
                    if(tree==null)activity.contentResolver.delete(uri,null,null)
                    else DocumentsContract.deleteDocument(activity.contentResolver,uri)
                }
            }
            activity.runOnUiThread{
                busy=false
                if(!activity.isDestroyed){
                    val message=if(result.isSuccess)
                        "${batch.size} 張海報已儲存至 ${if(tree==null)"Download/TWBoardingScanner" else "選取的資料夾"}"
                    else "儲存失敗，請重試或分享：${result.exceptionOrNull()?.message}"
                    toast(message)
                }
            }
        }
    }

    private fun share(){
        if(files.size!=PosterCatalog.count||files.any{!it.isFile}){
            toast("海報檔案不完整，請重新匯出");return
        }
        runCatching{
            val uris=ArrayList(files.map{FileProvider.getUriForFile(activity,
                activity.packageName+".posters",it)})
            val intent=Intent(Intent.ACTION_SEND_MULTIPLE).apply{
                type="image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData=ClipData.newUri(activity.contentResolver,"${PosterCatalog.summary} 海報",uris.first()).apply{
                    uris.drop(1).forEach{addItem(ClipData.Item(it))}
                }
            }
            activity.startActivity(Intent.createChooser(intent,"分享 ${uris.size} 張掃描海報"))
        }.onFailure{toast("無法開啟分享：${it.message}")}
    }
    private fun toast(message:String)=Toast.makeText(activity,message,Toast.LENGTH_LONG).show()
    fun close(){
        dialog?.dismiss()
        executor.shutdownNow()
    }
}
