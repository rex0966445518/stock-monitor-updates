package com.rex.twboardingscanner.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object AppUpdateInstaller {
    @Suppress("DEPRECATION")
    fun code(p:PackageInfo)=if(Build.VERSION.SDK_INT>=28)p.longVersionCode else p.versionCode.toLong()
    @Suppress("DEPRECATION")
    fun installed(c:Context)=c.packageManager.getPackageInfo(c.packageName,0)
    @Suppress("DEPRECATION")
    private fun signatures(p:PackageInfo):Set<String> {
        val a=if(Build.VERSION.SDK_INT>=28)p.signingInfo?.apkContentsSigners else p.signatures
        return a.orEmpty().map{signature->MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(""){"%02x".format(it)}}.toSet()
    }
    /**
     * Android allows same-versionCode APK replacement with the same signer.
     * Some beta APKs reused codes across different human-readable versions.
     * Never allow a lower versionCode: that is an Android package downgrade.
     */
    internal fun isNewer(availableCode:Long,availableName:String,installedCode:Long,installedName:String?):Boolean {
        if(availableCode>installedCode)return true
        if(availableCode<installedCode)return false
        fun parts(name:String?):List<Long>? {
            if(name==null||!name.matches(Regex("[0-9]+(\\.[0-9]+)*")))return null
            return name.split('.').map{it.toLongOrNull()?:return null}
        }
        val available=parts(availableName)?:return false
        val current=parts(installedName)?:return false
        for(index in 0 until maxOf(available.size,current.size)){
            val a=available.getOrElse(index){0L}
            val b=current.getOrElse(index){0L}
            if(a!=b)return a>b
        }
        return false
    }
    internal fun isNewer(s:AppUpdateSpec,installed:PackageInfo)=
        isNewer(s.versionCode,s.versionName,code(installed),installed.versionName)

    internal fun checkIdentity(s:AppUpdateSpec,packageName:String,versionCode:Long,versionName:String?,installedCode:Long,installedSigners:Set<String>,apkSigners:Set<String>,installedVersionName:String?=null){
        check(packageName==AppUpdateSource.PACKAGE&&versionCode==s.versionCode&&versionName==s.versionName){"安裝包版本或套件名稱不符"}
        check(isNewer(versionCode,s.versionName,installedCode,installedVersionName)){"此安裝包版本不高於手機目前的版本"}
        check(installedSigners.isNotEmpty()&&installedSigners==apkSigners){"更新包簽章不同，無法覆蓋安裝"}
    }
    @Suppress("DEPRECATION")
    fun verify(c:Context,file:File,s:AppUpdateSpec){
        check(AppUpdateSource.validBytes(file,s)){"更新包不完整或校驗失敗，請重新下載"}
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk=c.packageManager.getPackageArchiveInfo(file.path,flags)?:error("Android 無法識別此安裝包")
        val current=c.packageManager.getPackageInfo(c.packageName,flags)
        checkIdentity(s,apk.packageName,code(apk),apk.versionName,code(current),signatures(current),signatures(apk),current.versionName)
        check((apk.applicationInfo?.minSdkVersion?:26)<=Build.VERSION.SDK_INT){"此更新不支援目前 Android 版本"}
    }
    fun intent(c:Context,file:File):Intent {
        val uri=FileProvider.getUriForFile(c,"${c.packageName}.posters",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
