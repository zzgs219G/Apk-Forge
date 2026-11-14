package com.apkforge.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 示例注入载荷：App 启动后弹一个对话框。
 *
 * 【为什么是这个形态】
 * 注入点在 Activity.onCreate 的**最开头**，此时 Activity 还没有 attach 完成、
 * 窗口也还没创建，直接 show 对话框会抛
 * `WindowManager$BadTokenException: Unable to add window -- token null is not for an application`。
 * 所以必须 post 到主线程消息队列，等 onCreate 返回、窗口就绪后再弹。
 *
 * 【为什么用 Activity 参数而不是 Context】
 * AlertDialog 需要一个可用的窗口 token，Activity 自带；传 ApplicationContext 会崩。
 * 因此签名固定为 `(Landroid/app/Activity;)V`，注入点用 invoke-static {p0}。
 *
 * 编译方式见 docs/载荷示例.md：
 *   javac -source 8 -target 8 -bootclasspath android.jar DemoDialog.java
 *   d8 --output . DemoDialog.class
 */
public final class DemoDialog {

    private DemoDialog() {
    }

    /** 注入目标。参数就是宿主 Activity（invoke-static {p0}）。 */
    public static void inject(Activity activity) {
        if (activity == null) {
            return;
        }
        final Activity host = activity;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                show(host);
            }
        }, 800L);
    }

    /** 想换 Context 参数时用这个重载，内部会转成 Dialog 并用 Activity token。 */
    public static void inject(Context context) {
        if (context instanceof Activity) {
            inject((Activity) context);
        }
    }

    private static void show(Activity host) {
        try {
            if (host.isFinishing()) {
                return;
            }
            new AlertDialog.Builder(host)
                    .setTitle("ApkForge")
                    .setMessage("注入成功：这段弹窗来自外部 dex。")
                    .setPositiveButton("知道了", null)
                    .setCancelable(true)
                    .show();
        } catch (Throwable ignored) {
            // 注入载荷必须吞掉自身异常，否则会把宿主 App 搞崩，
            // 排障时连"加载成功但弹窗失败"和"根本没加载"都分不清
        }
    }
}
