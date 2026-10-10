package com.rex.twboardingscanner.update

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rex.twboardingscanner.ui.AppUpdateActivity
import com.rex.twboardingscanner.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val data="an apk byte stream used for resumable download testing".toByteArray()
    private val hash get()=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it)}
    private fun spec()=AppUpdateSpec(100,"0.5.0","https://github.com/${AppUpdateSource.REPO}/releases/download/android-v0.5.0/TWBoardingScanner-v0.5.0.apk",data.size.toLong(),hash,"新增一鍵更新、下載續傳，以及手動開始掃描。\n保留歷史日誌與條件設定。","2026-10-10T00:00:00Z")
    private val dir get()=File(app.filesDir,"update-test")
    @Before fun clean(){dir.deleteRecursively();AppUpdateStore(app).directory.deleteRecursively();app.getSharedPreferences("app_update",0).edit().clear().commit()}
    private class Reply(private val status:Int,private val body:InputStream,private val headers:Map<String,String> = emptyMap()):HttpURLConnection(URL("https://github.com")){
        override fun connect(){}
        override fun disconnect(){}
        override fun usingProxy()=false
        override fun getResponseCode()=status
        override fun getInputStream()=body
        override fun getHeaderField(name:String)=headers[name]
    }
    @Test fun manifestValidatesExactSourceAndNumericVersionRatherThanTextOrder(){
        val s=spec();assertEquals(s,AppUpdateSource.parse(s.json().toString()))
        listOf(
            s.json().put("url",s.url.replace("https://","http://")),
            s.json().put("url",s.url.replace("github.com","github.com.evil.example")),
            s.json().put("url",s.url+"?elsewhere=1"),
            s.json().put("url",s.url.replace("stock-monitor-updates","other")),
            s.json().put("packageName","different.app"),
            s.json().put("sha256","bad"),s.json().put("size",0)
        ).forEach{o->assertThrows(Exception::class.java){AppUpdateSource.parse(o.toString())}}
        val validSigners=setOf("same-certificate")
        AppUpdateInstaller.checkIdentity(s,AppUpdateSource.PACKAGE,100,"0.5.0",20,validSigners,validSigners)
        assertThrows(Exception::class.java){AppUpdateInstaller.checkIdentity(s,AppUpdateSource.PACKAGE,100,"0.5.0",101,validSigners,validSigners)}
        assertThrows(Exception::class.java){AppUpdateInstaller.checkIdentity(s,AppUpdateSource.PACKAGE,100,"0.5.0",100,validSigners,validSigners)}
        assertThrows(Exception::class.java){AppUpdateInstaller.checkIdentity(s,AppUpdateSource.PACKAGE,100,"0.5.0",20,validSigners,setOf("different"))}
        assertThrows(Exception::class.java){AppUpdateInstaller.checkIdentity(s,AppUpdateSource.PACKAGE,100,"0.5.0",20,emptySet(),emptySet())}
        assertThrows(Exception::class.java){AppUpdateInstaller.checkIdentity(s,"other.app",100,"0.5.0",20,validSigners,validSigners)}
        assertThrows(Exception::class.java){AppUpdateInstaller.checkIdentity(s,AppUpdateSource.PACKAGE,99,"0.5.0",20,validSigners,validSigners)}
    }
    @Test fun githubMetadataRedirectWorksAndUntrustedRedirectIsRejected(){
        val responses=java.util.ArrayDeque<Reply>()
        responses.add(Reply(302,ByteArrayInputStream(byteArrayOf()),mapOf("Location" to "https://release-assets.githubusercontent.com/example")))
        responses.add(Reply(200,ByteArrayInputStream(spec().json().toString().toByteArray())))
        val source=AppUpdateSource{responses.removeFirst()};assertEquals(spec(),source.latest());assertTrue(responses.isEmpty())
        var calls=0
        val bad=AppUpdateSource{calls++;Reply(302,ByteArrayInputStream(byteArrayOf()),mapOf("Location" to "https://other.example/fake"))}
        assertThrows(Exception::class.java){bad.latest()};assertEquals(1,calls)
    }
    @Test fun interruptedTransferResumesExactRangeAndPublishesOnlyCompleteVerifiedApk(){
        var sent=false
        val interrupted=object:InputStream(){
            override fun read():Int=throw IOException("network lost")
            override fun read(b:ByteArray,off:Int,len:Int):Int{if(sent)throw IOException("network lost");sent=true;data.copyInto(b,off,0,7);return 7}
        }
        val first=AppUpdateSource{Reply(200,interrupted)}
        assertThrows(IOException::class.java){first.download(spec(),dir)}
        assertEquals(7,File(dir,"${spec().key}.part").length().toInt());assertFalse(File(dir,"${spec().key}.apk").exists())
        val response=Reply(206,ByteArrayInputStream(data.copyOfRange(7,data.size)),mapOf("Content-Range" to "bytes 7-${data.size-1}/${data.size}"))
        val file=AppUpdateSource{response}.download(spec(),dir)
        assertEquals("bytes=7-",response.getRequestProperty("Range"));assertArrayEquals(data,file.readBytes());assertTrue(AppUpdateSource.validBytes(file,spec()))
        assertFalse(File(dir,"${spec().key}.part").exists())
        val cached=AppUpdateSource{throw AssertionError("Verified cache should not download again")}.download(spec(),dir)
        assertEquals(file,cached)
    }
    @Test fun fullResponseReplacesPartialAndBadRangesOrCorruptPackagesNeverBecomeReady(){
        dir.mkdirs();val part=File(dir,"${spec().key}.part");part.writeBytes(data.copyOfRange(0,7))
        val full=Reply(200,ByteArrayInputStream(data));val result=AppUpdateSource{full}.download(spec(),dir)
        assertEquals("bytes=7-",full.getRequestProperty("Range"));assertArrayEquals(data,result.readBytes())
        result.delete();part.writeBytes(data.copyOfRange(0,7))
        assertThrows(Exception::class.java){AppUpdateSource{Reply(206,ByteArrayInputStream(data),mapOf("Content-Range" to "bytes 0-${data.size-1}/${data.size}"))}.download(spec(),dir)}
        assertEquals(7,part.length().toInt());assertFalse(result.exists())
        part.delete()
        assertThrows(Exception::class.java){AppUpdateSource{Reply(200,ByteArrayInputStream(ByteArray(data.size){0}))}.download(spec(),dir)}
        assertFalse(part.exists());assertFalse(result.exists())
        assertThrows(Exception::class.java){AppUpdateSource{Reply(200,ByteArrayInputStream(data))}.download(spec(),dir,{true})}
        assertFalse(result.exists())
    }
    @Test fun pausedOrReplacedJobsCannotOverwriteTheActiveUpdateAndInstallerUsesGrantedContentUri(){
        val store=AppUpdateStore(app);val first=store.begin();store.setSpec(first,spec());store.status(first,"DOWNLOADING","下載中",7)
        store.cancel();store.status(first,"READY","late",spec().size);assertEquals("PAUSED",store.snapshot().getString("status"))
        val second=store.begin();store.setSpec(second,spec());store.status(first,"ERROR","late error")
        assertEquals(second,store.snapshot().getString("id"));assertEquals("QUEUED",store.snapshot().getString("status"))
        store.status(second,"READY","完成",spec().size);assertEquals("READY",store.snapshot().getString("status"))
        store.directory.mkdirs();val apk=store.apk(spec());apk.writeBytes(data)
        val intent=AppUpdateInstaller.intent(app,apk)
        assertEquals("content",intent.data!!.scheme);assertEquals("application/vnd.android.package-archive",intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
    }
    private fun find(v:View,p:(View)->Boolean):View?{if(p(v))return v;if(v is ViewGroup)for(i in 0 until v.childCount){find(v.getChildAt(i),p)?.let{return it}};return null}
    @Test fun updateScreenShowsProgressRecoveryReleaseNotesAndFitsPhone(){
        val store=AppUpdateStore(app);val id=store.begin();store.setSpec(id,spec());store.status(id,"DOWNLOADING","接續下載",20)
        val ctl=Robolectric.buildActivity(AppUpdateActivity::class.java).setup();val a=ctl.get()
        assertNotNull(find(a.window.decorView){it is TextView&&it.text.contains("接續下載")})
        assertFalse(find(a.window.decorView){it.tag=="update-primary"}!!.isEnabled)
        capture(a.window.decorView,"app-update-progress-360")
        store.status(id,"ERROR","下載中斷，請重試");a.render()
        assertTrue(find(a.window.decorView){it.tag=="update-primary"}!!.isEnabled)
        assertNotNull(find(a.window.decorView){it is TextView&&it.text.contains("重試／接續更新")})
        capture(a.window.decorView,"app-update-retry-360")
        ctl.pause().stop().destroy()
    }
    @Test fun enteringAndReturningToHomeDoesNotStartScanningAndManualButtonsAreVisible(){
        val ctl=Robolectric.buildActivity(MainActivity::class.java).setup();val a=ctl.get()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(16))
        assertNotNull(find(a.window.decorView){it is TextView&&it.text.toString()=="尚未開始掃描"})
        val scan=find(a.window.decorView){it is TextView&&it.text.toString()=="開始掃描"}!!
        assertTrue(scan.hasOnClickListeners());assertTrue(scan.isEnabled)
        assertNotNull(a.findViewById<View>(com.rex.twboardingscanner.R.id.settingsButton))
        capture(a.window.decorView,"main-manual-scan-update-360")
        ctl.pause().resume();shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(find(a.window.decorView){it is TextView&&it.text.toString()=="尚未開始掃描"})
        // Saving changed ABC conditions must not start a scan either.
        a.findViewById<View>(com.rex.twboardingscanner.R.id.settingsButton).performClick()
        val sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(sheet.findViewById<View>(com.rex.twboardingscanner.R.id.updateButton))
        sheet.findViewById<View>(com.rex.twboardingscanner.R.id.conditionAButton).performClick()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();sheet.dismiss()
        assertNotNull(find(a.window.decorView){it is TextView&&it.text.toString()=="尚未開始掃描"})
        ctl.pause().stop().destroy()
    }
    private fun capture(v:View,name:String){
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600)
        fun check(w:View){if(w.visibility!=View.VISIBLE)return;if(w is TextView&&w.layout!=null&&w.text.isNotEmpty())assertTrue("clipped: ${w.text}",w.layout.height<=w.height-w.compoundPaddingTop-w.compoundPaddingBottom+2);w.jumpDrawablesToCurrentState();if(w is ViewGroup)for(i in 0 until w.childCount)check(w.getChildAt(i))}
        check(v);val image=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);v.draw(Canvas(image))
        val file=File("build/ui-previews/$name.png");file.parentFile.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
