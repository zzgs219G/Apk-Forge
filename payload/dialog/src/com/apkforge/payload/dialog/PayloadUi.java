package com.apkforge.payload.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.util.Log;
import android.widget.TextView;

/**
 * 弹窗本体：四道闸门判定 + AlertDialog 构建 + 上色 + 强制模式。
 *
 * 【为什么用系统 AlertDialog 而不是 AppCompat / 自绘】宿主的主题是未知的。
 * 引 AppCompat 会在非 AppCompat 宿主上直接崩（主题不是 Theme.AppCompat），
 * 自绘又要接管窗口 token，风险更大。系统 AlertDialog 在任何宿主上都能活。
 *
 * 【为什么只有一个「弹一次」的总闸 + 三道可选闸】
 * 注入点在生命周期里可能被调用多次（每次进入前台）。三道可选闸覆盖真实需求：
 * 静默期（装完前 N 天别烦）、智能弹出（24h 内别重复）、强制（必须看）。
 */
public final class PayloadUi {

    private static final String TAG = "ApkForgeDialog";
    private static final long SMART_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private PayloadUi() { }

    static void show(final Activity act, final PayloadConfig.Config c, final PayloadStore store) {
        // 闸门 1：Activity 必须活着。死在 isFinishing 的 Activity 上 show() 会抛 BadTokenException
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        // 闸门 2：总开关只认「开」（fail-closed：漏写配置不该弹）
        if (!PayloadConfig.Config.isOn(c.switchOn)) return;

        // 闸门 3：静默期。期内不写时间戳，不消耗弹窗机会
        if (c.inSilencePeriod(store.firstRunTime())) return;

        // 闸门 4：智能弹出的 24h 滚动窗口。时间为 0（首装/清数据）→ 必弹
        boolean smart = PayloadConfig.Config.isOn(c.smart);
        if (smart && System.currentTimeMillis() - store.lastShowTime() < SMART_INTERVAL_MS) return;

        // 三按钮全关 = 关不掉的死弹窗，宁可不弹也不给宿主留一个无解的遮罩
        if (c.allButtonsOff()) {
            Log.w(TAG, "三个按钮都是「关」，弹出来用户关不掉，已跳过");
            return;
        }

        build(act, c, store, smart);
    }

    private static void build(final Activity act, final PayloadConfig.Config c,
                              final PayloadStore store, final boolean smart) {
        try {
            AlertDialog.Builder b;
            if ("默认白".equals(c.style)) {
                b = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
            } else if ("默认黑".equals(c.style)) {
                b = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
            } else if ("HOLO白".equals(c.style)) {
                b = new AlertDialog.Builder(act, AlertDialog.THEME_HOLO_LIGHT);
            } else if ("HOLO黑".equals(c.style)) {
                b = new AlertDialog.Builder(act, AlertDialog.THEME_HOLO_DARK);
            } else if ("传统".equals(c.style)) {
                b = new AlertDialog.Builder(act, AlertDialog.THEME_TRADITIONAL);
            } else {
                b = new AlertDialog.Builder(act);
            }

            // 标题/正文支持 HTML（<b>、<br>），老配置里就是这么写的
            if (!c.title.isEmpty()) b.setTitle(html(c.title));
            if (!c.message.isEmpty()) b.setMessage(html(c.message));

            // 三个按钮各挂各的开关。缺配置就是「关」，不会出现空文本按钮
            if (PayloadConfig.Config.isOn(c.agreeOn)) {
                b.setPositiveButton(c.agreeText, click(c.agreeEvent, store, c.version));
            }
            if (PayloadConfig.Config.isOn(c.refuseOn)) {
                b.setNegativeButton(c.refuseText, click(c.refuseEvent, store, c.version));
            }
            if (PayloadConfig.Config.isOn(c.neutralOn)) {
                b.setNeutralButton(c.neutralText, click(c.neutralEvent, store, c.version));
            }

            AlertDialog d = b.create();
            if ("关".equals(c.outsideClose)) d.setCanceledOnTouchOutside(false);
            d.show();

            // 智能弹出：弹出即记时间，开关自身闭环，不依赖有没有配「智能」动作按钮
            if (smart) store.markShown(System.currentTimeMillis());

            if (PayloadConfig.Config.isOn(c.forced)) {
                d.setCancelable(false);
                d.setCanceledOnTouchOutside(false);
                try {
                    java.lang.reflect.Field f = d.getClass().getSuperclass().getDeclaredField("mShowing");
                    f.setAccessible(true);
                    f.set(d, Boolean.FALSE);
                } catch (Exception e) {
                    // 灰名单可能拦掉；拦掉就是「强制不生效」，功能仍可用
                    Log.w(TAG, "强制模式反射失败: " + e.getMessage());
                }
            }

            tint(d, c);
        } catch (Throwable t) {
            // 弹窗是附加品，绝不能让宿主启动流程因为一段公告崩掉
            Log.w(TAG, "弹窗构建失败: " + t);
        }
    }

    /**
     * HTML 兼容。
     *
     * 【为什么只用单参数的那个 `fromHtml(String)`，哪怕它被标记为废弃】
     * 双参数版本（带 `FROM_HTML_MODE_LEGACY`）是 API 24 才有的，而本载荷的
     * min-api 是 21 —— 要能塞进老包。`d8 --min-api` 只影响 desugar 策略，
     * **不会**替我们挡住「调用了设备上不存在的 API」，那要到运行期才抛
     * NoSuchMethodError，正好砸在宿主启动那一瞬。
     * 单参数版本在 API 1~最新上都在，废弃标记只是「建议迁移」，不是移除。
     *
     * 验证方式：用 `platforms/android-21/android.jar` 编译一遍必须过
     * （见 payload/dialog/build.sh 的 API 兼容自检）。
     */
    @SuppressWarnings("deprecation")
    private static CharSequence html(String text) {
        return android.text.Html.fromHtml(text);
    }

    private static DialogInterface.OnClickListener click(final PayloadConfig.ButtonEvent ev,
                                                         final PayloadStore store,
                                                         final String version) {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PayloadEvent.dispatch(ev, dialog, store, version);
            }
        };
    }

    /** 上色。取不到 View 就保持主题默认——颜色是装饰，不该让弹窗失败。 */
    private static void tint(AlertDialog d, PayloadConfig.Config c) {
        int titleColor = PayloadConfig.color(c.titleColor, 0);
        int msgColor = PayloadConfig.color(c.messageColor, 0);
        try {
            java.lang.reflect.Field alert = AlertDialog.class.getDeclaredField("mAlert");
            alert.setAccessible(true);
            Object alertObj = alert.get(d);
            if (alertObj != null) {
                if (titleColor != 0) {
                    java.lang.reflect.Field f = alertObj.getClass().getDeclaredField("mTitleView");
                    f.setAccessible(true);
                    TextView t = (TextView) f.get(alertObj);
                    if (t != null) t.setTextColor(titleColor);
                }
                if (msgColor != 0) {
                    java.lang.reflect.Field f = alertObj.getClass().getDeclaredField("mMessageView");
                    f.setAccessible(true);
                    TextView m = (TextView) f.get(alertObj);
                    if (m != null) m.setTextColor(msgColor);
                }
            }
        } catch (Exception ignored) {
            // 反射路径因 ROM 而异，失败就退回主题色
        }

        setButtonColor(d, AlertDialog.BUTTON_POSITIVE, c.agreeColor, c.agreeOn);
        setButtonColor(d, AlertDialog.BUTTON_NEGATIVE, c.refuseColor, c.refuseOn);
        setButtonColor(d, AlertDialog.BUTTON_NEUTRAL, c.neutralColor, c.neutralOn);
    }

    private static void setButtonColor(AlertDialog d, int which, String color, String on) {
        if (!PayloadConfig.Config.isOn(on)) return;
        int c = PayloadConfig.color(color, 0);
        if (c == 0) return;
        try {
            d.getButton(which).setTextColor(c);
        } catch (Exception ignored) {
        }
    }

    /** 供调试：把当前烘进 dex 的配置摘要打出来，不弹窗。 */
    public static String describeConfig() {
        PayloadConfig.ParseResult r = PayloadConfig.parse(PayloadConfig.baked());
        if (!r.ok()) return "配置不可用：" + r.error;
        PayloadConfig.Config c = r.config;
        return "标题=" + c.title
                + " 总开关=" + c.switchOn
                + " 按钮=" + (PayloadConfig.Config.isOn(c.agreeOn) ? 1 : 0)
                + (PayloadConfig.Config.isOn(c.refuseOn) ? 1 : 0)
                + (PayloadConfig.Config.isOn(c.neutralOn) ? 1 : 0);
    }

    /** 颜色工具暴露给外部（导出时校验用）。 */
    static int debugColor(String v) { return PayloadConfig.color(v, Color.TRANSPARENT); }
}
