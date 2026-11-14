package com.apkforge.payload.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地弹窗载荷（drop-in payload）。
 *
 * 【这是什么】一段可以被 ApkForge 注入进任意 APK 的独立弹窗实现：
 * 注入一条 `invoke-static {p0}, Lcom/apkforge/payload/dialog/PayloadDialog;->inject(Landroid/app/Activity;)V`
 * 之后，宿主每次走到注入点就会尝试弹一次公告。
 *
 * 【和清洗自 tc 的在线版有什么区别】
 *  · 没有任何网络代码：配置从**宿主自己的 assets** 读，不需要服务器，
 *    也不会有「插件把用户设备当肉鸡去轮询别人服务器」这种隐患；
 *  · 不依赖 appcompat：宿主的主题未知，强行引 AppCompat 会在非 AppCompat 宿主上崩。
 *    这里一律用框架自带的 `android.app.AlertDialog`；
 *  · 配置只影响「显示什么」，不能执行任意动作：动作白名单是固定的五种，
 *    未知动作当「无操作」处理，绝不 eval 任何字符串。
 *
 * 【为什么配置放在 assets 里而不是烤进 dex】
 * 烤进 dex 意味着改一句文案要重新编译、重新注入、重新分发整个 APK。
 * 放在 assets 里，用户可以直接用 MT 改 `assets/apkforge_dialog.json`。
 * 代价是 dex 必须能被定位到配置——所以约定文件名固定，见 [ASSET_NAME]。
 */
public final class PayloadDialog {

    /** 宿主 assets 里的配置文件名。改这里必须同步 `PluginPack` 的打包脚本。 */
    public static final String ASSET_NAME = "apkforge_dialog.json";

    /** 同一进程内只弹一次：注入点可能被调用多次（每次进入模式）。 */
    private static boolean shown = false;

    /** 弱引用持有 Activity，避免把宿主的窗口泄漏出去。 */
    private static WeakReference<Activity> holder = new WeakReference<>(null);

    private PayloadDialog() {}

    // ────────────────────────── 注入入口 ──────────────────────────

    /** smali 调用行里被调用的方法：必须是 public static 且参数为 Activity。 */
    public static void inject(Activity activity) {
        show(activity);
    }

    /** 备用入口：注入点拿不到 Activity、只有 Context 时用这个。 */
    public static void inject(Context context) {
        if (context instanceof Activity) show((Activity) context);
    }

    // ────────────────────────── 主流程 ──────────────────────────

    /**
     * 弹窗主流程。
     *
     * 三道闸门，任一不过就静默返回——载荷的原则是「宁可什么都不做，
     * 也绝不让宿主的启动流程因为一段公告而崩掉」。
     */
    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (shown) return;

        final Config cfg;
        try {
            cfg = Config.load(activity);
        } catch (Throwable t) {
            // 配置缺失/损坏一律安静退出：这是可选的公告，不是宿主的功能
            Log.w("ApkForgeDialog", "配置读取失败: " + t);
            return;
        }
        if (cfg == null) return;
        if (!cfg.enabled) return;

        holder = new WeakReference<>(activity);
        shown = true;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                build(activity, cfg);
            }
        });
    }

    private static void build(final Activity act, Config c) {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(act);
            if (!TextUtils.isEmpty(c.title)) {
                b.setTitle(Html.fromHtml(c.title, Html.FROM_HTML_MODE_LEGACY));
            }
            if (!TextUtils.isEmpty(c.message)) {
                b.setMessage(Html.fromHtml(c.message, Html.FROM_HTML_MODE_LEGACY));
            }
            if (c.cancelable) {
                b.setCancelable(true);
            } else {
                b.setCancelable(false);
            }
            for (final Btn btn : c.buttons) {
                DialogInterface.OnClickListener l = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dispatch(act, btn);
                    }
                };
                switch (btn.slot) {
                    case 0: b.setPositiveButton(btn.text, l); break;
                    case 1: b.setNegativeButton(btn.text, l); break;
                    default: b.setNeutralButton(btn.text, l); break;
                }
            }
            AlertDialog d = b.create();
            d.show();
            tint(d, c);
        } catch (Throwable t) {
            Log.w("ApkForgeDialog", "弹窗构建失败: " + t);
        }
    }

    /** 按钮点击动作分发。白名单之外一律当「关掉」。 */
    private static void dispatch(Activity act, Btn btn) {
        try {
            if (!TextUtils.isEmpty(btn.toast)) {
                Toast.makeText(act, btn.toast, Toast.LENGTH_SHORT).show();
            }
            switch (btn.action) {
                case "url":
                    if (!TextUtils.isEmpty(btn.arg)) {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(btn.arg)));
                    }
                    break;
                case "exit":
                    act.finish();
                    break;
                case "custom":
                    // 自定义 jump：宿主自己的 scheme / activity
                    if (!TextUtils.isEmpty(btn.arg)) {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(btn.arg)));
                    }
                    break;
                case "none":
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w("ApkForgeDialog", "动作执行失败: " + t);
        }
    }

    /** 上色。用反射拿标题/正文 View，取不到就保持主题默认——绝不因此崩。 */
    private static void tint(AlertDialog d, Config c) {
        try {
            int id = d.getContext().getResources()
                    .getIdentifier("alertTitle", "id", "android");
            if (id != 0 && c.titleColor != 0) {
                android.widget.TextView t = d.findViewById(id);
                if (t != null) t.setTextColor(c.titleColor);
            }
            if (c.messageColor != 0) {
                android.widget.TextView m = d.findViewById(android.R.id.message);
                if (m != null) m.setTextColor(c.messageColor);
            }
        } catch (Throwable ignored) {
            // 关掉颜色只是不好看，不该让弹窗失败
        }
    }

    // ────────────────────────── 配置模型 ──────────────────────────

    /** 一个按钮。 */
    private static final class Btn {
        /** 0=positive 1=negative 2=neutral */
        int slot;
        String text;
        /** none / url / exit / custom */
        String action;
        String arg;
        String toast;
    }

    /** 解析后的配置。字段全部可选，缺省即「不显示」。 */
    private static final class Config {
        boolean enabled;
        boolean cancelable;
        String title;
        String message;
        int titleColor;
        int messageColor;
        final List<Btn> buttons = new ArrayList<>();

        /**
         * 从宿主 assets 读配置。
         *
         * 用 `getAssets()` 而不是文件路径：载荷 dex 被塞进宿主的 classesN.dex，
         * 它的资源通道就是宿主的 AssetManager，这是唯一稳定的读取方式。
         */
        static Config load(Context ctx) throws Exception {
            AssetManager am = ctx.getAssets();
            InputStream in;
            try {
                in = am.open(ASSET_NAME);
            } catch (Exception e) {
                // 没配置文件 = 不弹。这是最正常的「未配置」状态，不是错误
                return null;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            } finally {
                in.close();
            }
            return parse(bo.toString("UTF-8"));
        }

        static Config parse(String text) throws Exception {
            String s = stripComments(text).trim();
            if (s.isEmpty()) return null;
            JSONObject o = new JSONObject(s);
            Config c = new Config();
            c.enabled = !"关".equals(optString(o, "总开关", "开"));
            c.cancelable = "开".equals(optString(o, "点击外部关闭", "关"));
            c.title = optString(o, "标题", "");
            c.message = optString(o, "信息", "");
            c.titleColor = parseColor(optString(o, "标题颜色", ""), 0);
            c.messageColor = parseColor(optString(o, "信息颜色", ""), 0);

            JSONArray arr = o.optJSONArray("按钮");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    Btn btn = new Btn();
                    btn.slot = b.optInt("位置", i);
                    btn.text = optString(b, "文本", "");
                    btn.action = optString(b, "动作", "none");
                    btn.arg = optString(b, "参数", "");
                    btn.toast = optString(b, "提示", "");
                    if (!TextUtils.isEmpty(btn.text)) c.buttons.add(btn);
                }
            }
            return c;
        }

        private static String optString(JSONObject o, String k, String def) {
            if (!o.has(k) || o.isNull(k)) return def;
            return o.optString(k, def);
        }

        /** 只认 `#rrggbb` / `#aarrggbb`；写错就返回 0（= 用主题默认色）。 */
        static int parseColor(String v, int def) {
            if (TextUtils.isEmpty(v)) return def;
            try {
                return Color.parseColor(v.trim());
            } catch (Exception e) {
                return def;
            }
        }

        /**
         * 去掉 JSONC 注释。
         *
         * 必须区分字符串内外：`"标题": "// 不是注释"` 里的 `//` 不能被吃掉。
         * 只用内置实现，不引第三方 parser——载荷的体积直接等于注入进宿主的东西。
         */
        static String stripComments(String text) {
            StringBuilder out = new StringBuilder(text.length());
            boolean inStr = false;
            boolean esc = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (inStr) {
                    out.append(ch);
                    if (esc) esc = false;
                    else if (ch == '\\') esc = true;
                    else if (ch == '"') inStr = false;
                    continue;
                }
                if (ch == '"') {
                    inStr = true;
                    out.append(ch);
                    continue;
                }
                if (ch == '/' && i + 1 < text.length()) {
                    char nx = text.charAt(i + 1);
                    if (nx == '/') {
                        while (i < text.length() && text.charAt(i) != '\n') i++;
                        out.append('\n');
                        continue;
                    }
                    if (nx == '*') {
                        i += 2;
                        while (i + 1 < text.length()
                                && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) i++;
                        i++;
                        continue;
                    }
                }
                out.append(ch);
            }
            return out.toString();
        }
    }

    /** 供宿主/调试用：读到一个可展示的配置摘要，不弹窗。 */
    public static String debugSummary(Context ctx) {
        try {
            Config c = Config.load(ctx);
            if (c == null) return "没有找到 assets/" + ASSET_NAME;
            return "标题=" + c.title + " 按钮=" + c.buttons.size() + " 可关闭=" + c.cancelable;
        } catch (Throwable t) {
            return "配置损坏: " + t;
        }
    }
}
