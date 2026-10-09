package com.rex.twboardingscanner.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdateSpec(val versionCode:Long,val versionName:String,val url:String,val size:Long,val sha256:String,val notes:String,val publishedAt:String){
    fun json()=JSONObject().put("packageName",AppUpdateSource.PACKAGE).put("versionCode",versionCode).put("versionName",versionName).put("url",url).put("size",size).put("sha256",sha256).put("notes",notes).put("publishedAt",publishedAt)
    val key get()="$versionCode-${sha256.take(16)}"
}

class AppUpdateSource(private val open:(String)->HttpURLConnection={URL(it).openConnection() as HttpURLConnection}){
    companion object {
        private val downloadLock=Any()
        const val PACKAGE="com.rex.twboardingscanner"
        const val REPO="rex0966445518/stock-monitor-updates"
        const val PAGE="https://github.com/$REPO/releases/tag/latest-android"
        const val MANIFEST="https://github.com/$REPO/releases/download/latest-android/android-update.json"
        fun parse(raw:String):AppUpdateSpec {
            val o=JSONObject(raw)
            require(o.getString("packageName")==PACKAGE){"更新包名稱不符"}
            val code=o.getLong("versionCode");val version=o.getString("versionName");val size=o.getLong("size")
            val hash=o.getString("sha256").lowercase();val url=o.getString("url");val u=URL(url)
            require(o.getDouble("versionCode")==code.toDouble()&&o.getDouble("size")==size.toDouble()&&code>0&&version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))&&size in 1..268435456L&&hash.matches(Regex("[a-f0-9]{64}"))){"更新資訊格式錯誤"}
            require(u.protocol=="https"&&u.host=="github.com"&&u.port==-1&&u.userInfo==null&&u.query==null&&u.ref==null&&
                u.path in listOf("/$REPO/releases/download/latest-android/TWBoardingScanner-v$version.apk","/$REPO/releases/download/android-v$version/TWBoardingScanner-v$version.apk")){"更新來源不符"}
            return AppUpdateSpec(code,version,url,size,hash,o.optString("notes").take(30000),o.optString("publishedAt"))
        }
        fun digest(file:File):String {val md=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
        fun validBytes(file:File,s:AppUpdateSpec)=file.isFile&&file.length()==s.size&&digest(file)==s.sha256
    }
    private fun connection(initial:String,offset:Long=0):HttpURLConnection {
        var url=initial
        repeat(6){
            val u=URL(url)
            require(u.protocol=="https"&&u.userInfo==null&&u.port in listOf(-1,443)&&(u.host=="github.com"||u.host in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com"))){"更新重新導向來源不符"}
            val c=open(url);c.instanceFollowRedirects=false;c.connectTimeout=20000;c.readTimeout=30000;c.useCaches=false
            c.setRequestProperty("User-Agent","TWBoardingScanner-Updater");c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Cache-Control","no-cache")
            if(offset>0)c.setRequestProperty("Range","bytes=$offset-")
            val status=try{c.responseCode}catch(e:Exception){c.disconnect();throw e}
            if(status in listOf(301,302,303,307,308)){
                val location=c.getHeaderField("Location");c.disconnect();require(!location.isNullOrBlank()){"更新網址暫時失效"};url=URL(u,location).toString()
            }else return c
        }
        throw IOException("更新重新導向次數過多")
    }
    fun latest():AppUpdateSpec {
        val c=connection(MANIFEST+"?check="+System.currentTimeMillis())
        try{
            check(c.responseCode==200){"GitHub 更新資訊暫時無法取得（${c.responseCode}），請稍後重試"}
            val out=java.io.ByteArrayOutputStream()
            c.inputStream.use{input->val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=262144){"更新資訊過大"};out.write(b,0,n)}}
            return parse(out.toString("UTF-8"))
        }finally{c.disconnect()}
    }
    /** Resume only bytes belonging to this exact version and digest. Never append a full 200 response. */
    fun download(s:AppUpdateSpec,dir:File,cancel:()->Boolean={false},progress:(Long,String)->Unit={_,_->}):File {
        dir.mkdirs();val part=File(dir,"${s.key}.part");val apk=File(dir,"${s.key}.apk")
        synchronized(downloadLock){RandomAccessFile(File(dir,"download.lock"),"rw").use{guard->guard.channel.lock().use{
            check(!cancel()){"已取消更新"}
            if(validBytes(apk,s))return apk
            apk.delete();if(part.length()>s.size)part.delete()
            if(part.length()<s.size){
                val offset=part.length();val c=connection(s.url,offset)
                try{
                    val status=c.responseCode
                    val start=when(status){
                        200->0L
                        206->{
                            val match=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(c.getHeaderField("Content-Range").orEmpty())?:throw IOException("續傳範圍無效，請重試")
                            val from=match.groupValues[1].toLong();val to=match.groupValues[2].toLong();val total=match.groupValues[3].toLong()
                            check(from==offset&&total==s.size&&to==s.size-1&&to>=from){"續傳資料不一致，請重新檢查更新"};from
                        }
                        416->{part.delete();throw IOException("續傳位置已失效，請重試下載")}
                        else->throw IOException("GitHub 下載暫時無法取得（$status），請重試")
                    }
                    check(!cancel()){"已取消更新"}
                    RandomAccessFile(part,"rw").use{out->
                        if(start==0L)out.setLength(0);out.seek(start)
                        var bytes=start
                        progress(bytes,if(offset>0&&start==0L)"伺服器要求重新下載" else if(start>0)"接續下載" else "下載更新包")
                        c.inputStream.use{input->val buffer=ByteArray(65536);while(true){
                            check(!cancel()){"已取消更新"};val n=input.read(buffer);if(n<0)break
                            check(bytes+n<=s.size){"更新包大小超出預期"};out.write(buffer,0,n);bytes+=n;progress(bytes,"下載更新包")
                        }}
                        out.fd.sync();check(bytes==s.size){"下載中斷，已保留進度，請重試"}
                    }
                }finally{c.disconnect()}
            }
            check(!cancel()){"已取消更新"};progress(s.size,"驗證更新包")
            if(!validBytes(part,s)){part.delete();error("更新包校驗失敗，請重新下載")}
            check(part.renameTo(apk)){"無法保存更新包，請檢查儲存空間"};return apk
        }}}
    }
}
