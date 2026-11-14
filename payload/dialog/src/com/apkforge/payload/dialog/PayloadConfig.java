package com.apkforge.payload.dialog;

import android.graphics.Color;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹窗配置：**烘焙在 dex 里的那一份** + 它的解析逻辑。
 *
 * 【配置为什么在 dex 里，而不是在 assets 里】
 * 上一版把配置写成 `assets/apkforge_dialog.json` 一起塞进宿主包，代价是：
 *   · 注入产物凭空多出一个文件，用户会问「你往我包里塞了什么」；
 *   · 宿主的 assets 是它的自有资产，被第三方工具改写属于越界；
 *   · 一个配置要占两条通道（dex + 资产），任一通道掉了就是「注入成功、不弹窗」。
 * 现在配置就是 [BAKED] 这个字符串常量本身。ApkForge 导出时把它替换成真正的
 * JSON（见 `core/dex/PayloadBaker`），产物里只有 classesN.dex，没有多余文件。
 *
 * 【代价，必须说清楚】改文案要重新导出 dex 再重新注入。这是刻意的取舍：
 * 本地弹窗是「一次性投放」的东西，改文案的频率远低于「产物干净」的价值。
 *
 * 【配置格式】沿用清羽弹窗 7.0 的 JSONC 字段名（总开关 / 智能弹出 / 同意按钮事件 …），
 * 这样用户手上的老配置可以直接粘进来用，不需要学第二套字段。
 */
public final class PayloadConfig {

    /**
     * 配置占位符。
     *
     * ⚠️ 两个约束，改这里前先读：
     *
     * 1. 它必须由**方法返回**，不能写成 `static final String`。写成 final 常量，
     *    javac 会把字符串**内联到每一个使用点**，dex 里就会出现好几份拷贝，
     *    `PayloadBaker` 无法确定该替换哪一份（而且替换了一处、别处仍是占位符，
     *    表现为「注入成功、不弹窗」）。方法返回保证全 dex 只有一条 const-string。
     *
     * 2. 它与 `PayloadBaker.PLACEHOLDER` 逐字一致，`PluginPayloadTest` 钉住这条等式，
     *    并断言整份 dex 里只出现一次。
     */
    public static String baked() {
        return "__APKFORGE_CONFIG__";
    }

    private PayloadConfig() { }

    /** 是否已经被烘焙成真正的配置（占位串不是 JSON）。 */
    public static boolean isBaked(String text) {
        if (text == null) return false;
        String t = stripComments(text).trim();
        return t.startsWith("{");
    }

    // ────────────────────────── 解析入口 ──────────────────────────

    /** 解析结果：成 or 不成，失败原因必须是一句人话（会被打进 logcat）。 */
    public static final class ParseResult {
        public final Config config;
        public final String error;

        private ParseResult(Config config, String error) {
            this.config = config;
            this.error = error;
        }

        public boolean ok() { return config != null; }
    }

    public static ParseResult parse(String text) {
        if (text == null) return new ParseResult(null, "配置为空");
        String stripped = stripComments(text).trim();
        if (stripped.isEmpty()) return new ParseResult(null, "配置为空（去掉注释后没有内容）");
        if (!stripped.startsWith("{")) {
            return new ParseResult(null, "配置不是 JSON 对象（是不是忘了烘焙？）");
        }

        Map<String, String> kv = new LinkedHashMap<String, String>();
        ButtonEvent agree = ButtonEvent.none();
        ButtonEvent refuse = ButtonEvent.none();
        ButtonEvent neutral = ButtonEvent.none();
        try {
            JSONObject root = new JSONObject(stripped);
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String k = it.next();
                Object raw = root.opt(k);
                if (k.endsWith("按钮事件")) {
                    ButtonEvent ev = parseEvent(raw);
                    if (k.startsWith("同意")) agree = ev;
                    else if (k.startsWith("拒绝")) refuse = ev;
                    else if (k.startsWith("中立")) neutral = ev;
                    continue;
                }
                kv.put(k, flatten(raw));
            }
        } catch (Exception e) {
            // 解析失败不当场崩：能读出多少算多少，读不出就是空表（上面的 isEmpty 会兜住）
            return new ParseResult(null, "配置 JSON 解析失败：" + e.getMessage());
        }
        if (kv.isEmpty()) return new ParseResult(null, "配置里没有任何字段，检查括号是否配对");
        return new ParseResult(new Config(kv, agree, refuse, neutral), null);
    }

    /**
     * 去掉 JSONC 注释。
     *
     * 必须区分字符串内外：`"网址": "https://…"` 里的 `//` 不是注释。
     * 手写配置的人习惯写注释，不认注释等于让他每次导出都被拒绝。
     */
    public static String stripComments(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false, inLine = false, inBlock = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inLine) {
                if (c == '\n') { inLine = false; out.append(c); }
                continue;
            }
            if (inBlock) {
                if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) { out.append(s.charAt(++i)); continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; out.append(c); continue; }
            if (c == '/' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '/') { inLine = true; i++; continue; }
                if (n == '*') { inBlock = true; i++; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }

    // ────────────────────────── 事件 ──────────────────────────

    /**
     * 一个按钮点击后要做什么。
     *
     * 【为什么是结构化三字段而不是一条拼串】老版本把「动作 + 参数 + 提示」拼成
     * `网址http://…提示正在跳转` 再 indexOf 切回去，参数里出现「提示」两个字就被腰斩。
     * 结构化之后这类歧义从根上不存在。[legacyRaw] 只用于兼容老配置。
     */
    public static final class ButtonEvent {
        /** 内部关键字：网址 / 退出 / 智能 / 无 / 提示 / QQ名片 / QQ群 / QQ咨询 */
        public final String kind;
        public final String arg;
        public final String toast;
        public final String legacyRaw;

        private ButtonEvent(String kind, String arg, String toast, String legacyRaw) {
            this.kind = kind;
            this.arg = arg;
            this.toast = toast;
            this.legacyRaw = legacyRaw;
        }

        public static ButtonEvent none() { return new ButtonEvent("无", "", "", null); }

        public static ButtonEvent of(String kind, String arg, String toast) {
            return new ButtonEvent(kind, arg, toast, null);
        }

        public static ButtonEvent legacy(String raw) { return new ButtonEvent("无", "", "", raw); }

        public boolean isLegacy() { return legacyRaw != null; }
    }

    /** 人类可读动作名 → 内部关键字。老配置里这几种写法都出现过，等价。 */
    private static final Map<String, String> ACTION_ALIAS = new HashMap<String, String>();
    static {
        ACTION_ALIAS.put("打开网页", "网址");
        ACTION_ALIAS.put("打开链接", "网址");
        ACTION_ALIAS.put("网址", "网址");
        ACTION_ALIAS.put("关闭应用", "退出");
        ACTION_ALIAS.put("退出", "退出");
        ACTION_ALIAS.put("退出应用", "退出");
        ACTION_ALIAS.put("记住版本", "智能");
        ACTION_ALIAS.put("智能", "智能");
        ACTION_ALIAS.put("无操作", "无");
        ACTION_ALIAS.put("无", "无");
        ACTION_ALIAS.put("仅提示", "提示");
        ACTION_ALIAS.put("提示", "提示");
        ACTION_ALIAS.put("QQ名片", "QQ名片");
        ACTION_ALIAS.put("QQ群", "QQ群");
        ACTION_ALIAS.put("QQ咨询", "QQ咨询");
    }

    /** 可选动作（UI 下拉就用这份，顺序即显示顺序）。 */
    public static final String[] ACTIONS = {
            "无", "网址", "退出", "提示", "智能", "QQ名片", "QQ群", "QQ咨询",
    };

    private static ButtonEvent parseEvent(Object v) {
        if (v == null || v == JSONObject.NULL) return ButtonEvent.none();
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.isEmpty() ? ButtonEvent.none() : ButtonEvent.legacy(s);
        }
        if (!(v instanceof JSONObject)) {
            String s = flatten(v);
            return s.isEmpty() ? ButtonEvent.none() : ButtonEvent.legacy(s);
        }
        JSONObject o = (JSONObject) v;
        String action = o.optString("动作", "").trim();
        if (action.isEmpty()) return ButtonEvent.none();
        String mapped = ACTION_ALIAS.get(action);
        // 未知动作不猜：按「无」处理，绝不能因为写错一个词就退出宿主或跳链
        if (mapped == null) mapped = "无";
        String arg;
        if ("网址".equals(mapped)) {
            arg = o.optString("网址", "").trim();
            if (arg.isEmpty()) arg = o.optString("参数", "").trim();
        } else if (mapped.startsWith("QQ")) {
            arg = o.optString("QQ号", "").trim();
            if (arg.isEmpty()) arg = o.optString("参数", "").trim();
        } else {
            arg = "";
        }
        return ButtonEvent.of(mapped, arg, o.optString("提示", "").trim());
    }

    /** 值归一化：数组拼成多行；Boolean 转「开/关」（下游只认「开」，不归一会 fail-open）。 */
    private static String flatten(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return ((Boolean) v) ? "开" : "关";
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                // 用 <br> 而不是 \n：正文走 Html.fromHtml，\n 在 HTML 里会被渲染成空格
                if (i > 0) sb.append("<br>");
                sb.append(a.optString(i, ""));
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    // ────────────────────────── 配置模型 ──────────────────────────

    public static final class Config {
        public final Map<String, String> kv;

        public final String switchOn;
        public final String smart;
        public final String delayDays;
        public final String version;
        public final String forced;
        public final String outsideClose;
        public final String style;
        public final String dim;

        public final String title;
        public final String titleColor;
        public final String message;
        public final String messageColor;

        public final String agreeOn, agreeText, agreeColor;
        public final ButtonEvent agreeEvent;
        public final String refuseOn, refuseText, refuseColor;
        public final ButtonEvent refuseEvent;
        public final String neutralOn, neutralText, neutralColor;
        public final ButtonEvent neutralEvent;

        private Config(Map<String, String> kv, ButtonEvent agree, ButtonEvent refuse, ButtonEvent neutral) {
            this.kv = kv == null ? Collections.<String, String>emptyMap() : kv;
            switchOn = value("总开关");
            smart = value("智能弹出");
            delayDays = value("静默天数");
            version = value("版本");
            forced = value("强制性");
            outsideClose = value("点击外部关闭弹窗");
            style = value("弹窗风格");
            dim = value("外部背景透明");

            title = value("标题");
            titleColor = value("标题颜色");
            message = value("信息");
            messageColor = value("信息颜色");

            agreeOn = value("同意按钮");
            agreeText = value("同意按钮文本");
            agreeColor = value("同意按钮颜色");
            agreeEvent = agree;

            refuseOn = value("拒绝按钮");
            refuseText = value("拒绝按钮文本");
            refuseColor = value("拒绝按钮颜色");
            refuseEvent = refuse;

            neutralOn = value("中立按钮");
            neutralText = value("中立按钮文本");
            neutralColor = value("中立按钮颜色");
            neutralEvent = neutral;
        }

        private String value(String key) {
            String v = kv.get(key);
            return v == null ? "" : v.trim();
        }

        /** 开关判定：只认精确「开」。缺失/写错一律当关（fail-closed）。 */
        public static boolean isOn(String v) { return "开".equals(v); }

        /** 静默天数：关了/空/非法 → 0（宁可弹，也不能永久不弹）。 */
        public static int parseDays(String raw) {
            if (raw == null) return 0;
            String s = raw.trim();
            if (s.isEmpty() || "关".equals(s) || "0".equals(s)) return 0;
            try {
                int n = Integer.parseInt(s);
                return n > 0 ? n : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public boolean inSilencePeriod(long firstRunTime) {
            int days = parseDays(delayDays);
            if (days <= 0 || firstRunTime == 0L) return false;
            return System.currentTimeMillis() - firstRunTime < days * 24L * 60L * 60L * 1000L;
        }

        /** 三个按钮全关 = 用户关不掉的死弹窗，这种配置宁可整只不弹。 */
        public boolean allButtonsOff() {
            return !isOn(agreeOn) && !isOn(refuseOn) && !isOn(neutralOn);
        }

        public List<String> issues() {
            List<String> out = new ArrayList<String>();
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(message)) out.add("标题与信息都为空");
            if (!switchOn.isEmpty() && !isOn(switchOn) && !"关".equals(switchOn)) {
                out.add("总开关=\"" + switchOn + "\"，只认「开」或「关」");
            }
            String[] names = {"同意", "拒绝", "中立"};
            String[] ons = {agreeOn, refuseOn, neutralOn};
            String[] texts = {agreeText, refuseText, neutralText};
            for (int i = 0; i < names.length; i++) {
                if (isOn(ons[i]) && TextUtils.isEmpty(texts[i])) {
                    out.add(names[i] + "按钮：开着但没写文本");
                }
            }
            return out;
        }
    }

    /** `#rrggbb` / `#aarrggbb`；写错返回 0（= 用宿主主题默认色），不让颜色错误毁掉弹窗。 */
    public static int color(String v, int def) {
        if (TextUtils.isEmpty(v)) return def;
        try {
            return Color.parseColor(v.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
