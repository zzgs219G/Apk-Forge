// 文件路径：app/src/main/java/com/xixin/box/wrapper/ui/color/ColorRoleOverride.kt
package com.xixin.apkforge.ui.lifted.color

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 颜色走廊的「角色覆盖表」—— 用户为某个 [ColorRole] 指定了非默认槽位时记在这里。
 *
 * 设计三原则（缺一不可）：
 * 1. **空表 = 零行为变化**。未覆盖的角色一律回落到 [ColorRole.defaultSlot]，
 *    与改动前的硬编码值逐像素一致。用户不碰走廊，App 观感 100% 不变。
 * 2. **即时生效**。用 `mutableStateOf` 承载，任何角色改动触发 Compose 重组，
 *    无需重启、无需重新生成 colorScheme。
 * 3. **一键可弃**。[resetAll] 清空全部覆盖并落盘，立刻回到官方默认。
 *
 * 存储格式：`role_override_<RoleName>` = `<SlotName>`（如 `role_override_DockIndicator=Primary`）。
 * 用纯 String 存枚举名而非序列化对象：跨版本增删枚举项时不会抛异常，
 * 找不到的项静默回落默认值（见 [slotOf] 的 firstOrNull 兜底）。
 */
object ColorRoleOverride {

    private const val PREFS_NAME = "color_role_override"
    private const val KEY_PREFIX = "role_override_"

    private var prefs: SharedPreferences? = null

    /** 覆盖表：role -> 用户选的槽位。空表表示全部走默认。 */
    private val _overrides = mutableStateOf<Map<ColorRole, ColorSlot>>(emptyMap())

    /** 只读快照，供 UI 与主题层读取。 */
    val overrides: Map<ColorRole, ColorSlot> get() = _overrides.value

    /**
     * 初始化：从磁盘载入覆盖表。由 [com.xixin.box.BaseActivity] 在启动时调用，
     * 与 `ThemeManager.init` 同一时机，保证首帧就带上用户的覆盖。
     */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val loaded = mutableMapOf<ColorRole, ColorSlot>()
        for (role in ColorRole.entries) {
            val slotName = p.getString(KEY_PREFIX + role.name, null) ?: continue
            // 枚举项可能随版本增删，找不到就跳过（回落默认），不抛异常
            val slot = ColorSlot.entries.firstOrNull { it.name == slotName } ?: continue
            loaded[role] = slot
        }
        _overrides.value = loaded
    }

    /** 某角色当前生效的槽位（未覆盖则返回默认槽位）。 */
    fun slotOf(role: ColorRole): ColorSlot = slotOf(role, isLight = false)

    /**
     * 某角色当前生效的槽位，按明暗模式解析。
     *
     * 优先级：颜色走廊的用户覆盖 > 浅色专用槽 [ColorRole.lightSlot]（仅浅色）> [ColorRole.defaultSlot]。
     * 用户一旦在走廊里显式选过槽位，两种模式都用它 —— 覆盖是全局的，不按模式分叉，
     * 否则"用户改了但只有一半界面变"更难排查。
     *
     * @param isLight 传 [ColorScheme.isLightMode] 的结果；默认 false 表示按深色解析（历史行为）。
     */
    fun slotOf(role: ColorRole, isLight: Boolean): ColorSlot =
        _overrides.value[role]
            ?: if (isLight) role.lightSlot ?: role.defaultSlot else role.defaultSlot

    /** 设置某角色的覆盖槽位；传 null 表示恢复该角色的默认。 */
    fun set(role: ColorRole, slot: ColorSlot?) {
        val next = _overrides.value.toMutableMap()
        if (slot == null || slot == role.defaultSlot) {
            next.remove(role)
            prefs?.edit()?.remove(KEY_PREFIX + role.name)?.apply()
        } else {
            next[role] = slot
            prefs?.edit()?.putString(KEY_PREFIX + role.name, slot.name)?.apply()
        }
        _overrides.value = next
    }

    /** 清空全部覆盖，回到官方默认。 */
    fun resetAll() {
        _overrides.value = emptyMap()
        prefs?.edit()?.clear()?.apply()
    }

    /**
     * 构建完整的「角色 → 颜色」表（12 个角色全覆盖，无缺项）。
     * **这是全 App 唯一的颜色出口**：由 [com.xixin.box.AppTheme] /
     * `BaseActivity.setAppUI` 算出后经 [LocalColorRoles] 下发。
     *
     * alpha 规则：浓度是**角色**的属性，不随槽位覆盖而变。
     * 例：若某角色 defaultAlpha 非 1f，用户在走廊把它覆盖到别的槽位后仍是「该槽位 @ 原浓度」。
     * ⚠️ 2026-10 起全项目已无 defaultAlpha ≠ 1f 的角色（底栏指示器回归官方不透明色阶），
     *    此规则为通用契约保留，勿据此假设当前存在半透明角色。
     */
    fun buildTable(scheme: ColorScheme): Map<ColorRole, Color> {
        val isLight = scheme.isLightMode()
        return ColorRole.entries.associateWith { role ->
            val base = scheme.colorOf(slotOf(role, isLight))
            if (role.defaultAlpha < 1f) base.copy(alpha = role.defaultAlpha) else base
        }
    }
}

/**
 * 由 [ColorScheme] 自身判定浅色模式。
 *
 * 阈值 0.5 的安全边界极大：M3 浅色 `surface` 为 tone 98（相对亮度 ≈0.95），
 * 深色为 tone 6（≈0.005），任何种子色 / 调色风格都不会跨过 0.5。
 *
 * ⚠️ 为什么不要求调用方传 `isDark`：主题有 `BaseActivity.setAppUI` 与 `AppTheme` 两个入口，
 *    传参就得两处各自维护、任一处漏传就会让"浅色专用槽"静默失效。从 scheme 反推只有一个真相源。
 */
fun ColorScheme.isLightMode(): Boolean = surface.luminance() > 0.5f

/**
 * 全局「角色 → 颜色」表。由 [com.xixin.box.AppTheme] 在 colorScheme 生成后提供。
 *
 * ⚠️ 默认值是 **null** 而非某个空表：null 表示"没有走廊覆盖"，
 * 此时组件自行回落到角色槽位（浅色优先 `role.lightSlot`，否则 `role.defaultSlot`）——
 * 与主题层 [ColorRoleOverride.buildTable] 的解析规则完全一致。这样 Preview、测试、
 * 以及任何未套 AppTheme 的场景都不会因为缺 Provider 而崩或显示错色。
 */
val LocalColorRoles = compositionLocalOf<Map<ColorRole, Color>?> { null }

/**
 * 组件侧的统一取值入口 —— **所有需要颜色的 UI 都调这个，不要直接写 colorScheme.xxx**。
 *
 * 未提供 [LocalColorRoles]（如 Preview）时自动回落到角色当前槽位
 * （浅色模式优先 [ColorRole.lightSlot]），与主题层算出的结果一致。
 * 提供了则用走廊里用户选定的槽位，实现"改一处、全局即时生效"。
 *
 * 用法：
 * ```
 * .background(roleColor(ColorRole.DockIndicator))
 * Text(color = roleColor(ColorRole.DockActiveContent))
 * ```
 */
@Composable
fun roleColor(role: ColorRole): Color {
    LocalColorRoles.current?.get(role)?.let { return it }
    val scheme = MaterialTheme.colorScheme
    val base = scheme.colorOf(ColorRoleOverride.slotOf(role, scheme.isLightMode()))
    return if (role.defaultAlpha < 1f) base.copy(alpha = role.defaultAlpha) else base
}
