package dev.hoshino.bridge;

import android.os.ParcelFileDescriptor;
import android.os.Binder;
import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Instantiated only by the official Shizuku UserService loader, under its real adb/root uid. */
public final class PrivilegedShellService extends IShizukuShell.Stub {
    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelledIds = new ConcurrentHashMap<>();
    private final ExecutorService readers = Executors.newCachedThreadPool();
    private final int ownerUid;
    public PrivilegedShellService() { ownerUid=-1; }
    public PrivilegedShellService(Context context) { ownerUid=context.getApplicationInfo().uid; }
    private void owner() { if(ownerUid<0 || Binder.getCallingUid()!=ownerUid)throw new SecurityException("Shizuku v13 or newer is required"); }
    private static final class Run {
        final Process process;
        final AtomicInteger group = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicLong spilled = new AtomicLong();
        final AtomicBoolean limit = new AtomicBoolean();
        Run(Process process) { this.process=process; }
        void stop() {
            cancelled.set(true);
            int pid=group.get();
            if(pid>1)try { Os.kill(-pid, OsConstants.SIGKILL); } catch(Exception ignored) {}
            process.destroyForcibly();
        }
    }
    private static final class Output {
        final ByteArrayOutputStream preview = new ByteArrayOutputStream();
        long bytes;
    }
    private Output drain(InputStream raw, ParcelFileDescriptor target, Run run, int cap, boolean header) throws Exception {
        Output value=new Output();
        try(InputStream input=raw; OutputStream file=new ParcelFileDescriptor.AutoCloseOutputStream(target)) {
            if(header) {
                ByteArrayOutputStream pid=new ByteArrayOutputStream();
                int next;
                while((next=input.read())!=-1 && next!='\n') { if(pid.size()>=16)throw new IOException("Invalid process header");pid.write(next); }
                if(pid.size()==0 && run.cancelled.get())return value;
                run.group.set(Integer.parseInt(pid.toString("US-ASCII")));
                if(run.cancelled.get())run.stop();
            }
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1) {
                value.bytes+=count;
                if(run.spilled.addAndGet(count)>64L*1024*1024) { run.limit.set(true);run.stop();break; }
                file.write(buffer,0,count);
                int room=cap-value.preview.size();if(room>0)value.preview.write(buffer,0,Math.min(room,count));
            }
        } catch(IOException failure) {
            // Killing an owned command closes Process streams while reader threads are draining.
            // Preserve the partial output only for our explicit timeout/cancel/limit path.
            if(!run.cancelled.get())throw failure;
        }
        return value;
    }
    @Override public String execute(String id,String command,int timeoutMs,int previewBytes,ParcelFileDescriptor stdout,ParcelFileDescriptor stderr) {
        Run run=null;
        try {
            owner();
            if(id==null || !id.matches("[a-f0-9-]{36}") || command==null || command.length()>16000 || runs.size()>=4)throw new IllegalArgumentException("Invalid system command request");
            if(cancelledIds.remove(id)!=null)return "{\"cancelled\":true,\"exitCode\":130,\"stdout\":\"\",\"stderr\":\"\"}";
            if(!new File("/system/bin/setsid").canExecute())throw new IOException("System setsid is unavailable");
            int timeout=Math.max(100,Math.min(timeoutMs,600000));int cap=Math.max(1024,Math.min(previewBytes,65536));
            // The command is one positional argument; the header cannot be changed by its contents.
            Process child=new ProcessBuilder("/system/bin/setsid","/system/bin/sh","-c","printf '%s\\n' \"$$\"; exec /system/bin/sh -c \"$1\"","hoshino",command)
                .directory(new File("/data/local/tmp")).start();
            child.getOutputStream().close();
            run=new Run(child);
            if(runs.putIfAbsent(id,run)!=null) { run.stop();throw new IllegalArgumentException("Duplicate command id"); }
            if(cancelledIds.remove(id)!=null)run.stop();
            final Run active=run;
            Future<Output> out=readers.submit(()->drain(child.getInputStream(),stdout,active,cap,true));
            Future<Output> err=readers.submit(()->drain(child.getErrorStream(),stderr,active,cap,false));
            boolean timedOut=!child.waitFor(timeout,TimeUnit.MILLISECONDS);
            if(timedOut)run.stop();
            child.waitFor(5,TimeUnit.SECONDS);
            Output output=out.get(5,TimeUnit.SECONDS),errors=err.get(5,TimeUnit.SECONDS);
            return new JSONObject().put("uid",android.os.Process.myUid()).put("cwd","/data/local/tmp")
                .put("exitCode",child.exitValue()).put("timedOut",timedOut).put("cancelled",run.cancelled.get() && !timedOut)
                .put("stdout",output.preview.toString("UTF-8")).put("stderr",errors.preview.toString("UTF-8"))
                .put("stdoutBytes",output.bytes).put("stderrBytes",errors.bytes)
                .put("truncated",run.limit.get() || output.bytes>cap || errors.bytes>cap).toString();
        } catch(Exception failure) {
            if(run!=null)run.stop();
            // Never include the command, environment or output in exceptions or Android logs.
            Throwable cause=failure instanceof ExecutionException && failure.getCause()!=null ? failure.getCause() : failure;
            return "{\"error\":\"System command failed: "+cause.getClass().getSimpleName()+"\"}";
        } finally { if(run!=null)runs.remove(id,run);try{stdout.close();}catch(Exception ignored){}try{stderr.close();}catch(Exception ignored){} }
    }
    @Override public void cancel(String id) { owner();if(cancelledIds.size()>128)cancelledIds.clear();cancelledIds.put(id,true);Run run=runs.get(id);if(run!=null)run.stop(); }
    private String pm(String... args) throws Exception {
        java.util.List<String> command=new java.util.ArrayList<>();command.add("/system/bin/pm");java.util.Collections.addAll(command,args);
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();p.getOutputStream().close();
        Future<String> output=readers.submit(()->{try(InputStream in=p.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("pm output too large");out.write(b,0,n);}return out.toString("UTF-8");}});
        if(!p.waitFor(90,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Package installation timed out");}String text=output.get(5,TimeUnit.SECONDS);if(p.exitValue()!=0)throw new IOException("Package manager rejected request");return text;
    }
    @Override public String installApk(ParcelFileDescriptor apk,long bytes){
        int session=-1;
        try {owner();if(bytes<1 || bytes>300L*1024*1024)throw new IllegalArgumentException();
            String created=pm("install-create","-r","-d","--pkg","dev.hoshino.app","-S",Long.toString(bytes));
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(created);if(!match.find())throw new IOException("No install session");session=Integer.parseInt(match.group(1));
            Process p=new ProcessBuilder("/system/bin/pm","install-write","-S",Long.toString(bytes),Integer.toString(session),"base.apk","-").redirectErrorStream(true).start();
            Future<String> result=readers.submit(()->{try(InputStream in=p.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException();out.write(b,0,n);}return out.toString("UTF-8");}});
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(apk);OutputStream out=p.getOutputStream()){byte[] b=new byte[65536];long copied=0;int n;while((n=in.read(b))!=-1){copied+=n;if(copied>bytes)throw new IOException();out.write(b,0,n);}if(copied!=bytes)throw new IOException();}
            if(!p.waitFor(90,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException();}if(p.exitValue()!=0 || !result.get(5,TimeUnit.SECONDS).contains("Success"))throw new IOException();
            String committed=pm("install-commit",Integer.toString(session));if(!committed.contains("Success"))throw new IOException();return "{\"installed\":true,\"method\":\"shizuku\"}";
        } catch(Exception failure){if(session>=0)try{pm("install-abandon",Integer.toString(session));}catch(Exception ignored){}return "{\"installed\":false,\"error\":\"Package manager rejected installation\"}";}
        finally {try{apk.close();}catch(Exception ignored){}}
    }
    @Override public void destroy() { for(Run run:runs.values())run.stop();readers.shutdownNow();System.exit(0); }
}
