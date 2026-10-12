package com.rex.twboardingscanner.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.rex.twboardingscanner.R
import com.rex.twboardingscanner.domain.RadarType
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate

/** Regression for v0.4.45: the export loop produced four PNGs but preview had THREE titles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterExportRegressionTest {
    class Host:AppCompatActivity(){
        internal lateinit var exporter:PosterExport
        override fun onCreate(state:Bundle?){
            setTheme(R.style.Theme_TWBoardingScanner)
            super.onCreate(state)
            exporter=PosterExport(this) // Register launcher before Activity reaches STARTED.
        }
        override fun onDestroy(){exporter.close();super.onDestroy()}
    }
    private fun find(root:View,tag:String):View?{
        if(root.tag==tag)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)find(root.getChildAt(i),tag)?.let{return it}
        return null
    }
    // FileProvider caches roots per authority across Robolectric application sandboxes.
    // Clear only the test JVM's static cache after using its share chooser; otherwise
    // the unrelated updater URI test could inherit this test's temporary filesDir.
    @After fun clearRobolectricFileProviderRoots(){
        val field=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible=true
        @Suppress("UNCHECKED_CAST")
        val cache=field.get(null) as MutableMap<Any,Any>
        synchronized(cache){cache.clear()}
    }
    private fun prepared(host:Host):List<File>{
        val folder=File(host.cacheDir,"posters/regression-4");folder.mkdirs()
        return PosterCatalog.sections.map {section->
            val file=File(folder,PosterCatalog.fileName("2026-10-12",section))
            val bitmap=Bitmap.createBitmap(200,350,Bitmap.Config.RGB_565)
            try{
                bitmap.eraseColor(Color.rgb(10,30,50))
                file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            }finally{bitmap.recycle()}
            file
        }
    }
    private fun useBatch(poster:PosterExport,files:List<File>){
        PosterExport::class.java.getDeclaredField("files").apply{isAccessible=true}.set(poster,files)
    }
    private fun call(poster:PosterExport,name:String){
        PosterExport::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(poster)
    }
    @Test fun everyGeneratedTypeHasAUniquePosterFilenamePreviewButtonAndSafePreviewSample(){
        assertEquals(4,RadarType.entries.size)
        assertEquals(RadarType.entries.toList(),PosterCatalog.sections.map{it.radar})
        assertEquals(listOf("A 起漲","B 反轉","C 爆量","D 主力"),PosterCatalog.sections.map{it.label})
        assertEquals(4,PosterCatalog.sections.map{PosterCatalog.fileName("2026-10-12",it)}.distinct().size)
        assertEquals(1,PosterCatalog.previewSample(800,1400))
        val sample=PosterCatalog.previewSample(1080,35890)
        assertTrue(sample>=32)
        assertTrue(1080/sample<=880)
        assertTrue(35890/sample<=1760)
        val activity=Robolectric.buildActivity(Host::class.java).setup()
        val files=prepared(activity.get())
        assertEquals(4,files.size)
        val tabBar=PosterCatalog.tabs(activity.get()){}
        assertEquals(4,tabBar.childCount)
        assertNotNull(find(tabBar,"poster-tab-D"))
        files.forEach{it.delete()}
        activity.pause().stop().destroy()
    }
    @Test fun actualPreviewRendersAllFourTabsWithoutThePreviousIndexCrash(){
        val activity=Robolectric.buildActivity(Host::class.java).setup()
        val host=activity.get()
        val files=prepared(host)
        useBatch(host.exporter,files)
        call(host.exporter,"preview")
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        val decor=dialog.window!!.decorView
        for(section in PosterCatalog.sections){
            val control=find(decor,"poster-tab-${section.letter}")
            assertNotNull("Missing ${section.label} preview tab",control)
            assertTrue(control!!.performClick())
            assertEquals("${section.label} 海報預覽",
                (find(decor,"poster-preview-image") as ImageView).contentDescription?.toString())
        }
        assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString().contains("4 張"))
        assertTrue(dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString().contains("4 張"))
        dialog.dismiss()
        activity.pause().stop().destroy()
        files.forEach{it.delete()}
    }
    @Test fun sharingIncludesFourDistinctPosterUrisRatherThanHardcodedThree(){
        val controller=Robolectric.buildActivity(Host::class.java).setup()
        val host=controller.get()
        val files=prepared(host)
        useBatch(host.exporter,files)
        call(host.exporter,"share")
        val chooser=Shadows.shadowOf(host).nextStartedActivity
        assertNotNull("Share chooser was not launched",chooser)
        assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        val share=chooser.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java)
        assertNotNull(share)
        assertEquals(Intent.ACTION_SEND_MULTIPLE,share!!.action)
        assertEquals("image/png",share.type)
        val uris=share.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(4,uris?.size)
        assertEquals(4,share.clipData?.itemCount)
        assertEquals(4,uris?.distinct()?.size)
        controller.pause().stop().destroy()
        files.forEach{it.delete()}
    }
}
