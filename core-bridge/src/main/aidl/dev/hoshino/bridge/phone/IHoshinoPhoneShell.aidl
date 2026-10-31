// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone;

import android.os.Bundle;
import android.view.Surface;

// Runs with the shell uid (2000) in either the built-in helper (started over wireless debugging)
// or a Shizuku UserService. Only the Hoshino app uid may call it (checked in onTransact).
interface IHoshinoPhoneShell {
    // Virtual display rendered into the app's Surface. Returns the display id, -1 on failure.
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=started 1=user is using it on the main screen (not started) 2=no launcher entry 3=error 7=used by another task
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    // Allow-listed navigation keys only (back, enter, tab, dpad, del...). Delivered to displayId.
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    // One finger stroke on an own display: xs/ys pixels, times ms relative to the down event.
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 8;
    String topPackage(int displayId) = 9;
    int displayOfTask(int taskId) = 10;
    // [uid, pid] of the service process; the app verifies the binder lives in the process that handed it off.
    int[] identity() = 11;
    // Shell command for setup/repair (pm grant, settings put, appops). Returns exitCode + output.
    Bundle runCommand(String command) = 12;
    // Ctrl+A / Ctrl+V only, own displays only.
    boolean injectCtrlKey(int displayId, int keyCode) = 13;
    // Releases displays and restores anything changed for the app; process stays.
    void releaseAll() = 14;
    // "builtin" or "shizuku": which host runs this service.
    String host() = 15;

    // ---- WP-2 clipboard / typing: transaction codes 16-20 belong to WP-2 (19-20 reserved). ----
    // Results are never 0: an older helper answers unknown codes with an empty reply, which reads as 0.
    // Puts text on the clipboard as the shell, only if the user's current clip could be read first (it is kept
    // in this process for restoreClip). 1=set 2=unreadable (clipboard left untouched) 3=failed.
    // Marked sensitive (A13+) and with the "copied" overlay suppressed. Restored automatically after 10 s if the
    // app never calls restoreClip (e.g. it crashed mid-paste).
    int setClipText(String text) = 16;
    // Gives the user's clip back (cleared if it was empty); left alone if the user copied something else
    // meanwhile or it cannot be read now. No-op when nothing is borrowed.
    void restoreClip() = 17;
    // Types printable text key by key into an own display (no clipboard). 1=typed 2=some character has no
    // key, nothing sent 3=failed.
    int injectText(int displayId, String text) = 18;
    void destroy() = 16777114;
}
