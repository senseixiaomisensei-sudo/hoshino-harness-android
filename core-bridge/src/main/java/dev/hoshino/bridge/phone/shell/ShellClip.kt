// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

/**
 * Pure decisions for borrowing the clipboard for one paste (no Android types, unit-tested).
 *
 * A read can be silently blocked by the system: the clip comes back null while `hasPrimaryClip()` or the
 * description still say there is something. Then "really empty" and "present but hidden" cannot be told
 * apart, so it is always treated as unreadable and never cleared or overwritten.
 */
object ShellClip {
    enum class Read {
        /** Clip, presence flag and description all say nothing is there. */
        Empty,
        /** The content was read. */
        Has,
        /** Something is there but it could not be read. */
        Unreadable,
    }

    fun read(clipPresent: Boolean, hasPrimaryClip: Boolean, descriptionPresent: Boolean): Read = when {
        clipPresent -> Read.Has
        hasPrimaryClip || descriptionPresent -> Read.Unreadable
        else -> Read.Empty
    }

    /** What is on the clipboard right before restoring. */
    enum class Now { Ours, Empty, Other, Unreadable }

    enum class Restore {
        /** Put the user's clip back. */
        PutBack,
        /** The user's clipboard was empty: remove ours. */
        Clear,
        /** Do not touch it: the user copied something else meanwhile, or it cannot be read now. */
        Leave,
    }

    /**
     * [priorEmpty]: the clipboard was read as really empty before the swap (an unreadable one is never swapped).
     * If someone cleared ours (e.g. the system's auto-clear) and the user had something, it is put back: an empty
     * clipboard is not something new the user copied, and not putting it back would lose the user's clip.
     */
    fun restore(priorEmpty: Boolean, now: Now): Restore = when (now) {
        Now.Ours -> if (priorEmpty) Restore.Clear else Restore.PutBack
        Now.Empty -> if (priorEmpty) Restore.Leave else Restore.PutBack
        Now.Other, Now.Unreadable -> Restore.Leave
    }
}

/** The clipboard as seen by the shell uid; [C] is `ClipData` on Android and a fake in tests. */
interface ClipStore<C : Any> {
    /** The shell holds READ_CLIPBOARD_IN_BACKGROUND and the read-clipboard app-op is allowed. */
    fun canRead(): Boolean
    fun current(): C?
    fun hasClip(): Boolean
    fun hasDescription(): Boolean
    /** [clip] is the one [ClipSwap] put there with text [ours]. */
    fun isOurs(clip: C, ours: String): Boolean
    /** Puts our text (labelled, marked sensitive, "copied" overlay suppressed). */
    fun putOurs(text: String)
    /** Puts the user's own clip back unchanged (overlay suppressed). */
    fun putBack(clip: C)
    fun clear()
}

/**
 * Borrow-and-restore state for the clipboard, held in the helper process (it outlives a crashed app, so the
 * user's clip can still be given back). Not thread-safe by itself: [PhoneShellService] serialises calls.
 *
 * Rule: the clipboard is only touched when the user's current clip could be read. Otherwise [set] returns
 * [CLIP_UNREADABLE] and nothing was changed, so the caller types another way.
 */
class ClipSwap<C : Any>(private val store: ClipStore<C>) {
    private var saved: C? = null
    private var holding = false
    private var ours: String? = null
    /** Bumped on every swap so a late watchdog restore cannot undo a newer swap. */
    var generation = 0L
        private set

    val isHolding: Boolean get() = holding

    fun set(text: String): Int {
        if (!holding) {
            if (!store.canRead()) return CLIP_UNREADABLE
            val prior = store.current()
            when (ShellClip.read(prior != null, store.hasClip(), store.hasDescription())) {
                ShellClip.Read.Unreadable -> return CLIP_UNREADABLE
                ShellClip.Read.Empty, ShellClip.Read.Has -> Unit
            }
            saved = prior
            holding = true
            generation++
        }
        store.putOurs(text)
        ours = text
        return CLIP_SET
    }

    /** Gives the user's clip back (see [ShellClip.restore]); idempotent. Returns the action taken, null when nothing was held. */
    fun restore(): ShellClip.Restore? {
        if (!holding) return null
        val prior = saved
        val action = ShellClip.restore(prior == null, now())
        try {
            when (action) {
                ShellClip.Restore.PutBack -> store.putBack(prior!!)
                ShellClip.Restore.Clear -> store.clear()
                ShellClip.Restore.Leave -> Unit
            }
        } finally {
            saved = null
            holding = false
            ours = null
        }
        return action
    }

    /** Watchdog: restores only if the swap [gen] is still the one held (the app died before calling [restore]). */
    fun restoreIfStill(gen: Long): ShellClip.Restore? = if (holding && generation == gen) restore() else null

    private fun now(): ShellClip.Now {
        val clip = store.current()
        return when (ShellClip.read(clip != null, store.hasClip(), store.hasDescription())) {
            ShellClip.Read.Empty -> ShellClip.Now.Empty
            ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
            ShellClip.Read.Has -> if (ours != null && store.isOurs(clip!!, ours!!)) ShellClip.Now.Ours else ShellClip.Now.Other
        }
    }

    companion object {
        // Never 0: an older helper without these methods answers an unknown transaction with an empty reply,
        // which the AIDL proxy reads as 0. 0 must therefore mean "not supported", never "done".
        const val CLIP_SET = 1
        const val CLIP_UNREADABLE = 2
        const val CLIP_FAILED = 3
    }
}
