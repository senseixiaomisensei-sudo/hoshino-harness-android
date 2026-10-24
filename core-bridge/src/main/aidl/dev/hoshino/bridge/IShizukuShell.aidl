package dev.hoshino.bridge;
import android.os.ParcelFileDescriptor;
interface IShizukuShell {
    void destroy() = 16777114;
    String execute(String id, String command, int timeoutMs, int previewBytes, in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr) = 1;
    void cancel(String id) = 2;
}
