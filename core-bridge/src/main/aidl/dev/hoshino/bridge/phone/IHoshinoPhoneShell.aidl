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
    void destroy() = 16777114;
}
