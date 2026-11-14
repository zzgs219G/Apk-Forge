package com.apkforge.payload.dialog;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「什么时候弹过」的持久化。
 *
 * 只做读写，一条业务判断都不放这里——「是否在静默期」「智能间隔过没过」
 * 归 {@link PayloadConfig.Config} 判断。混在一起的话，改判定规则要动存储层。
 *
 * 【为什么用宿主的 SharedPreferences】载荷 dex 跑在宿主的进程里，`context`
 * 就是宿主，它天然有一份可写存储，不需要自己建文件、不需要申请权限。
 *
 * 【为什么自建文件名而不用 getDefaultSharedPreferences】后者是宿主的默认偏好文件，
 * 键名与宿主撞车的概率不为零（宿主自己也可能有 `version` 这样的键）。
 * 独立文件 `apkforge_dialog` 把命名空间隔开，卸载宿主时一起清掉，正合适。
 */
public final class PayloadStore {

    private static final String FILE_NAME = "apkforge_dialog";
    private static final String KEY_LAST_SHOW = "last_show";
    private static final String KEY_FIRST_RUN = "first_run";
    private static final String KEY_SAVED_VER = "version";

    private final SharedPreferences prefs;

    private PayloadStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    private static volatile PayloadStore instance;

    public static PayloadStore get(Context context) {
        if (instance == null) {
            synchronized (PayloadStore.class) {
                if (instance == null) instance = new PayloadStore(context);
            }
        }
        return instance;
    }

    public long lastShowTime() {
        return prefs.getLong(KEY_LAST_SHOW, 0L);
    }

    public void markShown(long at) {
        prefs.edit().putLong(KEY_LAST_SHOW, at).apply();
    }

    /** 首次运行时间；0 表示还没记过，此时写入当前时间。 */
    public long firstRunTime() {
        long t = prefs.getLong(KEY_FIRST_RUN, 0L);
        if (t == 0L) {
            t = System.currentTimeMillis();
            prefs.edit().putLong(KEY_FIRST_RUN, t).apply();
        }
        return t;
    }

    /** 「智能」动作记下版本号备查（不参与是否再弹的判定）。 */
    public void saveVersion(String version) {
        prefs.edit().putString(KEY_SAVED_VER, version == null ? "" : version).apply();
    }
}
