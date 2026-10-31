package dev.hoshino.bridge.phone.shell

/**
 * Which text may be typed key by key into an own display ([PhoneShellService.injectText]); pure, unit-tested.
 * Only printable characters: a newline or tab would become Enter/Tab and could submit or move focus.
 * Whether each character has a key on the virtual keyboard is decided by KeyCharacterMap on the device;
 * anything it cannot map (most CJK text) is refused as a whole, before any key is sent.
 */
object ShellText {
    const val MAX_CHARS = 1_000

    // Never 0, see ClipSwap.CLIP_SET.
    const val TEXT_TYPED = 1
    /** Some character has no key; nothing was sent. */
    const val TEXT_UNMAPPABLE = 2
    const val TEXT_FAILED = 3

    fun typeable(text: String): Boolean =
        text.isNotEmpty() && text.length <= MAX_CHARS && text.all { c -> !Character.isISOControl(c) && !Character.isSurrogate(c) && c != '\u2028' && c != '\u2029' }
}
