-keep class dev.hoshino.bridge.PrivilegedShellService { *; }
# Phone helper: started by name through app_process / hosted by Shizuku UserService.
-keep class dev.hoshino.bridge.phone.shell.HelperMain { public static void main(java.lang.String[]); }
-keep class dev.hoshino.bridge.phone.shell.PhoneShellService { <init>(...); }
-keep class dev.hoshino.bridge.phone.shell.HelperHandoffProvider
