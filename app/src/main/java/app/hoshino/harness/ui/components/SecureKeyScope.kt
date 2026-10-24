package app.hoshino.harness.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

fun Context.activity(): Activity? = when(this) { is Activity -> this;is ContextWrapper -> baseContext.activity();else -> null }

/** Protect only the credential editor's lifetime, including screenshots of dialogs. */
@Composable fun SecureKeyScope() {
    val window=LocalContext.current.activity()?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
