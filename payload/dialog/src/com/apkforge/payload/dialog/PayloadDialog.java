package com.apkforge.payload.dialog;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

/**
 * 本地弹窗载荷的唯一入口（drop-in payload）。
 *
 * 【它是什么】一段可以被 ApkForge 注入进任意 APK 的独立弹窗实现。
 * 注入一条
 * `invoke-static {p0}, Lcom/apkforge/payload/dialog/PayloadDialog;->inject(Landroid/app/Activity;)V`
 * 之后，宿主走到注入点就会按配置弹一次公告。
 *
 * 【与线上版（清羽弹窗 7.0）的差别，以及为什么是这些差别】
 *  1. **不联网。** 线上版从 URL 拉配置，等于让宿主去轮询别人的服务器。
 *     本地版的配置直接烘焙在 dex 里（{@link PayloadConfig#baked()}），
 *     一次投放、一次生效，没有任何运行期网络调用。
 *  2. **配置不进 assets。** 上一版把 json 一起塞进宿主包里，多一条通道就多一个
 *     「注入成功、不弹窗」的失败面。现在产物里只有 dex。
 *  3. **保留完整的 7.0 能力**：三个按钮（同意/拒绝/中立）、五种弹窗风格、
 *     标题/正文/按钮文字与颜色、HTML 文案、智能弹出 24h、静默天数、强制性、
 *     点击外部关闭、背景透明、动作白名单（网址/退出/提示/智能/QQ 三种）、
 *     以及老式拼串事件的兼容。
 *     缺一样都会让用户的旧配置「升级后失效」，那是比少写代码更糟的事。
 *  4. **不依赖 appcompat。** 宿主主题未知，一律用框架自带的 `android.app.AlertDialog`。
 *
 * 【失败原则】任何一步出问题都静默退出（只 Log.w），绝不抛给宿主。
 * 这是一段公告，不是宿主的功能，不能因为它让 App 起不来。
 */
public final class PayloadDialog {

    private static final String TAG = "ApkForgeDialog";

    /** 同一进程只弹一次：注入点可能被调用很多次（每次进前台）。 */
    private static volatile boolean shown = false;

    private PayloadDialog() { }

    // ────────────────────────── 注入入口 ──────────────────────────

    /** smali 调用行指向的方法：public static，参数为宿主 Activity。 */
    public static void inject(Activity activity) {
        show(activity);
    }

    /** 备用入口：注入点只有 Context 时用（不是 Activity 就什么也不做）。 */
    public static void inject(Context context) {
        if (context instanceof Activity) show((Activity) context);
    }

    // ────────────────────────── 主流程 ──────────────────────────

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (shown) return;

        final PayloadConfig.ParseResult r;
        try {
            r = PayloadConfig.parse(PayloadConfig.baked());
        } catch (Throwable t) {
            Log.w(TAG, "配置读取失败: " + t);
            return;
        }
        if (!r.ok()) {
            Log.w(TAG, "配置不可用: " + r.error);
            return;
        }

        PayloadEvent.Holder.set(activity);
        final PayloadStore store = PayloadStore.get(activity);
        final PayloadConfig.Config c = r.config;

        // 配置有问题就在 logcat 里说清楚。这是「不弹窗」时唯一的线索——
        // 一句都不打的话，用户只能猜是没注入、还是被闸门拦了、还是配置写错了。
        for (String issue : c.issues()) Log.w(TAG, "配置告警: " + issue);

        // 注入点在 onCreate 开头，此时窗口 token 还没就绪，直接 show 会抛 BadTokenException。
        // post 一次让消息回到主线程队列末尾——onCreate 返回后窗口已就绪。
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // ⚠️ `shown` 必须在这里置位，不能在 post 之前。
                //    在 post 之前置位的话，Activity 若在 runnable 执行前被销毁
                //    （旋转、秒退），这一轮就白烧了一次机会，整个进程内再也不会尝试。
                //    而「被闸门拦下」也算一次明确的结论 —— 拦下就不再重试，
                //    否则每次进前台都走一遍闸门，纯属浪费。
                if (activity.isFinishing() || activity.isDestroyed()) return;
                shown = true;
                PayloadUi.show(activity, c, store);
            }
        });
    }

    /** 调试辅助：返回烘焙进 dex 的配置摘要（不弹窗）。 */
    public static String debugSummary() {
        return PayloadUi.describeConfig();
    }
}
