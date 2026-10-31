// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

/**
 * One readable element of a phone screen. [bounds] are normalised to 0..999 of the display
 * (left, top, right, bottom) so the model never deals with pixels; [px]/[py] are the pixel centre for gestures.
 * [ref] is stable across reads of the same session (see [RefStabilizer]).
 */
data class UiElement(
    val ref: String = "",
    val role: String,
    val name: String,
    val bounds: List<Int>,
    val px: Int = 0,
    val py: Int = 0,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** The name shown is a placeholder/hint, not typed text. */
    val hint: Boolean = false,
    val placeholder: String? = null,
    val password: Boolean = false,
    val searchField: Boolean = false,
    /** Text recognised by OCR (no accessibility node behind it): tap by coordinates only. */
    val ocr: Boolean = false,
) {
    val cx: Int get() = if (bounds.size == 4) (bounds[0] + bounds[2]) / 2 else 0
    val cy: Int get() = if (bounds.size == 4) (bounds[1] + bounds[3]) / 2 else 0
    val interactive: Boolean get() = clickable || longClickable || editable || scrollable
}

/** One read of a display. [hash] fingerprints every visible node (used for change and quiet detection). */
data class ScreenSnapshot(
    val snapshotId: String,
    val packageName: String?,
    val width: Int,
    val height: Int,
    val elements: List<UiElement>,
    val hash: Long = 0,
    /** Package names of non-app windows on top (system dialogs, choosers). */
    val overlayPackages: List<String> = emptyList(),
    val keyboardShown: Boolean = false,
    /** Accessibility tree was too thin and OCR text was merged in. */
    val ocrUsed: Boolean = false,
    /** Window marked secure (FLAG_SECURE) or capture failed: the model must not expect a picture. */
    val secure: Boolean = false,
) {
    val texts: List<String> get() = elements.map { it.name }.filter { it.isNotBlank() }
    fun byRef(ref: String): UiElement? = elements.firstOrNull { it.ref == ref }
}

enum class IrrevAction { send, pay, delete, order, submit }

data class Irreversible(val action: IrrevAction, val matched: String)
