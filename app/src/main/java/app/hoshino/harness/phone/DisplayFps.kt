package app.hoshino.harness.phone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The persisted frame-rate choice (SharedPreferences `phone_display`, key `fps`). Not a system change: no consent needed. */
class PhoneDisplayFps private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("phone_display", Context.MODE_PRIVATE)
    private val _fps = MutableStateFlow(FpsMath.clamp(prefs.getInt(KEY, FpsMath.DEFAULT_FPS)))
    val fps: StateFlow<Int> = _fps.asStateFlow()
    private val _zeroCopy = MutableStateFlow(prefs.getBoolean(KEY_ZERO_COPY, false))
    /** Experimental GPU (zero-copy) preview; default off. Applies to the next agent display. */
    val zeroCopy: StateFlow<Boolean> = _zeroCopy.asStateFlow()

    fun setZeroCopy(on: Boolean) {
        prefs.edit().putBoolean(KEY_ZERO_COPY, on).apply()
        _zeroCopy.value = on
    }

    fun set(value: Int) {
        val v = FpsMath.clamp(value)
        if (v == _fps.value) return
        prefs.edit().putInt(KEY, v).apply()
        _fps.value = v
    }

    companion object {
        private const val KEY = "fps"
        private const val KEY_ZERO_COPY = "zeroCopy"
        @Volatile private var instance: PhoneDisplayFps? = null
        fun of(context: Context): PhoneDisplayFps = instance ?: synchronized(this) { instance ?: PhoneDisplayFps(context.applicationContext).also { instance = it } }
    }
}
