/*
 * 黑龙江工商学院 VPN 客户端 —— 崩溃诊断
 * 目的：主界面若崩溃，把异常堆栈显示出来，便于快速定位（截图即可反馈）
 */
package de.blinkt.openvpn.core;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HibuCrashHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler mDefault;
    private final Context mCtx;

    private HibuCrashHandler(Context ctx, Thread.UncaughtExceptionHandler def) {
        mCtx = ctx.getApplicationContext();
        mDefault = def;
    }

    public static void install(Context ctx) {
        try {
            Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
            if (def instanceof HibuCrashHandler) return;
            Thread.setDefaultUncaughtExceptionHandler(new HibuCrashHandler(ctx, def));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        String trace = buildTrace(t, e);

        // 1) 落一份文件，方便导出
        try {
            File dir = mCtx.getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, "last_crash.txt");
                FileWriter fw = new FileWriter(f, false);
                fw.write(trace);
                fw.close();
            }
        } catch (Throwable ignored) {
        }

        // 2) 拉起崩溃详情页（让用户能截图）
        try {
            Intent i = new Intent(mCtx, de.blinkt.openvpn.activities.CrashReportActivity.class);
            i.putExtra("crash_trace", trace);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mCtx.startActivity(i);
            Thread.sleep(3000);
        } catch (Throwable ignored) {
        }

        if (mDefault != null) {
            mDefault.uncaughtException(t, e);
        }
    }

    private String buildTrace(Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("线程: " + (t == null ? "?" : t.getName()));
        pw.println("机型: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        pw.println("系统: Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        pw.println("版本: 黑龙江工商学院VPN客户端");
        pw.println("--------------------------------------------------");
        if (e != null) e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
