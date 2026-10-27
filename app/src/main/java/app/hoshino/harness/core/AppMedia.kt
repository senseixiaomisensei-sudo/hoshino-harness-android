package app.hoshino.harness.core

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.PixelCopy
import android.view.WindowManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Native window capability; no MediaProjection or background clipboard access. */
object AppSurface {
    private var current=WeakReference<Activity>(null)
    @Volatile var foreground=false; private set
    fun resume(activity:Activity){current=WeakReference(activity);foreground=true}
    fun pause(activity:Activity){if(current.get()===activity)foreground=false}
    fun activity():Activity=current.get()?.takeIf{foreground && !it.isFinishing} ?: error("请先回到 Hoshino 前台")
    suspend fun capture():Bitmap=withContext(Dispatchers.Main) {
        val activity=activity();check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0){"密钥编辑页面禁止截图"}
        val decor=activity.window.decorView;check(decor.width>0 && decor.height>0)
        val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
        suspendCancellableCoroutine {continuation->
            PixelCopy.request(activity.window,bitmap,{result->
                if(!continuation.isActive){bitmap.recycle();return@request}
                if(result==PixelCopy.SUCCESS && foreground && activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)continuation.resume(bitmap)
                else{bitmap.recycle();continuation.resumeWithException(IllegalStateException("窗口截图失败 ($result)"))}
            },Handler(Looper.getMainLooper()))
        }
    }
}

data class StoredAttachment(val attachment:Attachment,val file:File)
class AppMedia(private val context:Context) {
    private val imports=File(context.cacheDir,"imports").apply{mkdirs()}
    private val vault=dev.hoshino.bridge.ProviderStore(context)
    fun uri(file:File):Uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
    fun cameraFile():File=File(imports,"${UUID.randomUUID()}.jpg")
    suspend fun import(uri:Uri,kind:AttachmentKind):StoredAttachment=withContext(Dispatchers.IO) {
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0) else null} ?: "attachment"
        val file=File(imports,UUID.randomUUID().toString());var size=0L
        try{context.contentResolver.openInputStream(uri)!!.use{input->file.outputStream().use{out->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;size+=n;require(size<=1024L*1024*1024){"单个附件不能超过 1 GiB"};out.write(buffer,0,n)}}}}
        catch(error:Exception){file.delete();throw error}
        StoredAttachment(Attachment(file.name,vault.redact(name).take(180),kind,"${size/1024} KB"),file)
    }
    fun resolve(id:String):File {require(id.matches(Regex("[a-f0-9-]{36}(\\.jpg)?")));return File(imports,id).also{check(it.isFile){"附件已失效，请重新选择"}}}
    suspend fun keepOriginal(sessionId:String,attachment:Attachment):File=withContext(Dispatchers.IO) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,150}")))
        val dir=File(context.filesDir,"attachments/$sessionId").apply{mkdirs()}
        resolve(attachment.id).copyTo(File(dir,attachment.id),overwrite=true)
    }
    fun imageBlock(file:File,name:String):org.json.JSONObject {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0){"无法解码图片"}
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>3136)sample*=2
        val source=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample}) ?: error("图片解码失败")
        val scale=minOf(1f,1568f/maxOf(source.width,source.height))
        val image=if(scale<1f)Bitmap.createScaledBitmap(source,(source.width*scale).roundToInt().coerceAtLeast(1),(source.height*scale).roundToInt().coerceAtLeast(1),true) else source
        val bytes=ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,90,bytes)
        if(image!==source)image.recycle();source.recycle()
        return org.json.JSONObject().put("type","image").put("mediaType","image/jpeg").put("data",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)).put("name",name)
    }
    suspend fun save(bitmap:Bitmap):Uri=withContext(Dispatchers.IO) {
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"Hoshino-${System.currentTimeMillis()}.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Hoshino Harness");put(MediaStore.Images.Media.IS_PENDING,1)}
        val resolver=context.contentResolver;val result=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("无法写入相册")
        try{resolver.openOutputStream(result)!!.use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};resolver.update(result,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)}
        catch(error:Exception){resolver.delete(result,null,null);throw error}
        result
    }
    suspend fun shareImage(bitmap:Bitmap,clipboard:Boolean=false):Uri=withContext(Dispatchers.Main) {
        val file=withContext(Dispatchers.IO){val dir=File(context.cacheDir,"share").apply{mkdirs()};File(dir,"${UUID.randomUUID()}.png").also{it.outputStream().use{out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out)}}}
        val result=uri(file)
        if(clipboard){val clip=ClipData.newUri(context.contentResolver,"Hoshino",result);if(android.os.Build.VERSION.SDK_INT>=33)clip.description.extras=android.os.PersistableBundle().apply{putBoolean("android.content.extra.IS_SENSITIVE",true)};(context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)}
        else context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,result);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newRawUri("Hoshino",result)},"分享截图").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        result
    }
    fun shareFile(file:File,mime:String="application/octet-stream") {
        val uri=uri(file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newRawUri("Hoshino",uri)},"分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
