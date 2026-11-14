package com.apkforge.payload.dialog;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * 按钮点击动作分发。
 *
 * 【白名单是硬约束，不是讲究】这份配置最终会被打进别人的 APK，配置里写什么就执行什么，
 * 等于把「跳任意 scheme」的权限开放给写配置的人。不加白名单，`intent://`、
 * `file://`、`content://` 全都能被拉起——那是把用户设备上的文件当跳板。
 * 所以只放行 {@link #SAFE_SCHEMES} 里的几个。
 *
 * 【未知动作一律按「无」】写错一个词（比如「打开网站」）不该让宿主退出。
 * 静默降级成「只关掉弹窗」，比误触发退出/跳链安全得多。
 */
public final class PayloadEvent {

    /** scheme 白名单。mqqapi/mqqwpa 是 QQ 的深链协议。 */
    private static final String[] SAFE_SCHEMES = {"http", "https", "mqqapi", "mqqwpa"};

    /** 老式事件串的动作前缀，最长优先（4 字 → 3 字 → 2 字）。 */
    private static final String[] LEGACY_ACTIONS = {
            "打开网页", "打开链接", "关闭应用", "记住版本", "无操作", "仅提示",
            "QQ名片", "QQ群", "QQ咨询",
            "网址", "退出", "智能", "提示", "无",
    };

    private PayloadEvent() { }

    /** 弱引用持有 Activity：静态字段持有强引用会泄漏宿主窗口。 */
    static final class Holder {
        private static WeakReference<Activity> ref;

        static void set(Activity act) { ref = new WeakReference<Activity>(act); }

        static Activity get() { return ref == null ? null : ref.get(); }
    }

    static void dispatch(PayloadConfig.ButtonEvent ev, DialogInterface dialog,
                         PayloadStore store, String version) {
        if (ev == null) return;
        if (ev.isLegacy()) {
            dispatchLegacy(ev.legacyRaw, dialog, store, version);
            return;
        }
        if (!ev.toast.isEmpty()) toast(ev.toast);
        if ("无".equals(ev.kind)) {
            allowDismiss(dialog);
        } else if ("退出".equals(ev.kind)) {
            exitApp();
        } else if ("智能".equals(ev.kind)) {
            store.saveVersion(version);
            allowDismiss(dialog);
        } else if ("网址".equals(ev.kind)) {
            openUrl(ev.arg);
        } else if ("提示".equals(ev.kind)) {
            // 提示已在上方统一 toast
        } else if ("QQ名片".equals(ev.kind)) {
            openQQ("mqqapi://card/show_pslcard?src_type=internal&source=sharecard&version=1&uin=",
                    ev.arg, "");
        } else if ("QQ群".equals(ev.kind)) {
            openQQ("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=",
                    ev.arg, "&card_type=group&source=qrcode");
        } else if ("QQ咨询".equals(ev.kind)) {
            openQQ("mqqwpa://im/chat?chat_type=wpa&uin=", ev.arg, "&version=1&src_type=web");
        }
    }

    /**
     * 老式事件串，如 `网址http://…提示正在跳转`。
     *
     * 保留 substring 语义是因为老配置都是这个形态，硬改会让它们全部失效。
     * 只收敛一处：从**最后一个**「提示」处切分（约定提示永远在末尾），
     * 参数中间出现「提示」两字时 URL 不会被腰斩。
     */
    private static void dispatchLegacy(String raw, DialogInterface dialog,
                                       PayloadStore store, String version) {
        if (raw == null) return;
        String ev = raw.trim();
        if (ev.isEmpty()) return;

        String toastText = "";
        String body = ev;
        int tip = ev.lastIndexOf("提示");
        if (tip >= 0) {
            toastText = ev.substring(tip + 2);
            body = ev.substring(0, tip);
        }

        String action = stripActionPrefix(body);
        if (!toastText.isEmpty()) toast(toastText);

        if ("网址".equals(action) || "打开网页".equals(action) || "打开链接".equals(action)) {
            openUrl(body.substring(action.length()).trim());
        } else if ("QQ名片".equals(action) || "QQ群".equals(action) || "QQ咨询".equals(action)) {
            handleLegacyQQ(body);
        } else if ("提示".equals(action) || "仅提示".equals(action)) {
            toast(body.substring(action.length()));
        } else if ("无".equals(action) || "无操作".equals(action)) {
            allowDismiss(dialog);
        } else if ("退出".equals(action) || "关闭应用".equals(action)) {
            exitApp();
        } else if ("智能".equals(action) || "记住版本".equals(action)) {
            store.saveVersion(version);
            allowDismiss(dialog);
        }
    }

    private static String stripActionPrefix(String body) {
        for (int i = 0; i < LEGACY_ACTIONS.length; i++) {
            if (body.startsWith(LEGACY_ACTIONS[i])) return LEGACY_ACTIONS[i];
        }
        return "";
    }

    private static void handleLegacyQQ(String str) {
        if (str.contains("名片")) {
            openQQ("mqqapi://card/show_pslcard?src_type=internal&source=sharecard&version=1&uin=",
                    str, "");
        } else if (str.contains("群")) {
            openQQ("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=",
                    str, "&card_type=group&source=qrcode");
        } else if (str.contains("咨询")) {
            openQQ("mqqwpa://im/chat?chat_type=wpa&uin=", str, "&version=1&src_type=web");
        }
    }

    /** 白名单校验后跳转。不在表内只提示、不启动。 */
    private static void openUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) return;
        // 允许直接粘 HTML 链接：<a href="...">文字</a>
        int href = url.indexOf("href=\"");
        if (href >= 0) {
            String after = url.substring(href + 6);
            int quote = after.indexOf('"');
            url = quote > 0 ? after.substring(0, quote) : "";
        }
        url = url.trim();
        if (url.isEmpty()) return;

        String scheme = schemeOf(url);
        if (scheme == null || !isSafeScheme(scheme)) {
            toast("已阻止跳转：" + (scheme == null ? "链接格式不对" : scheme + ":// 不在白名单"));
            return;
        }
        try {
            Activity a = Holder.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("没有能打开这个链接的应用");
        } catch (Exception e) {
            toast(String.valueOf(e));
        }
    }

    private static String schemeOf(String url) {
        int idx = url.indexOf("://");
        if (idx <= 0) return null;
        return url.substring(0, idx).toLowerCase(Locale.US);
    }

    private static boolean isSafeScheme(String scheme) {
        for (int i = 0; i < SAFE_SCHEMES.length; i++) {
            if (SAFE_SCHEMES[i].equals(scheme)) return true;
        }
        return false;
    }

    /** QQ 号只留数字：配置里写「QQ群 12345 提示加入」时中文不能进 URI。 */
    private static void openQQ(String prefix, String rawUin, String suffix) {
        try {
            String uin = rawUin == null ? "" : rawUin.replaceAll("\\D+", "");
            if (uin.isEmpty()) return;
            Activity a = Holder.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(prefix + uin + suffix)));
        } catch (ActivityNotFoundException e) {
            toast("没有安装手机 QQ");
        } catch (Exception e) {
            toast(String.valueOf(e));
        }
    }

    private static void exitApp() {
        try {
            Activity a = Holder.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            a.finish();
            Process.killProcess(Process.myPid());
            System.exit(0);
        } catch (Exception e) {
            toast(String.valueOf(e));
        }
    }

    /**
     * 反射把 Dialog.mShowing 恢复成 true，解除强制模式的关闭锁。
     *
     * 【已知风险，刻意保留】mShowing 是 non-SDK 字段，Android 9+ 灰名单下可能静默失效
     * （try/catch 兜不住「字段存在但被忽略」）。非强制模式下调用无害。
     */
    private static void allowDismiss(DialogInterface dialog) {
        try {
            java.lang.reflect.Field f = dialog.getClass().getSuperclass().getDeclaredField("mShowing");
            f.setAccessible(true);
            f.set(dialog, Boolean.TRUE);
        } catch (Exception ignored) {
            // 关不掉只是体验差，不该抛给宿主
        }
    }

    private static void toast(String msg) {
        toast(msg, Toast.LENGTH_LONG);
    }

    private static void toast(String msg, int duration) {
        try {
            Activity a = Holder.get();
            if (a == null || msg == null) return;
            Toast.makeText(a, msg, duration).show();
        } catch (Exception ignored) {
        }
    }
}
