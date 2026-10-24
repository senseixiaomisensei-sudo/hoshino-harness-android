package app.hoshino.harness.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.content.Intent
import android.os.PersistableBundle
import dev.hoshino.bridge.ProviderStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier

/** 点击 + 长按（会话长按菜单等）。 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.longPressClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** 复制到剪贴板（使用平台 API，避免 Compose 剪贴板 API 在各版本间的差异）。 */
fun copyToClipboard(context: Context, text: String, toast: String = "已复制") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val safe=ProviderStore(context).redact(text)
    val clip=ClipData.newPlainText("Hoshino",safe)
    if(android.os.Build.VERSION.SDK_INT>=33)clip.description.extras=PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true) }
    cm.setPrimaryClip(clip)
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

fun shareText(context: Context,text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,ProviderStore(context).redact(text)),"分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
