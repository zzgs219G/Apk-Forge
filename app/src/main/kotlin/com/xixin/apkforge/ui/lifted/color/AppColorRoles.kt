// 文件路径：app/src/main/java/com/xixin/box/wrapper/ui/color/AppColorRoles.kt
package com.xixin.apkforge.ui.lifted.color

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

// ── 分组常量：必须放**顶层**，不能放 enum 的 companion object ──
// ⚠️ Kotlin 初始化 enum 时会先构造全部 entry，此时 companion object 尚未初始化。
// 在 entry 的构造参数里引用 companion 成员 → 编译报
// "Companion object of enum class 'ColorSlot' is uninitialized here"
// （云端构建实测 60 条同类报错，全部指向本文件）。
// 顶层 `const val` 是编译期内联常量，不参与初始化顺序，可安全引用。
private const val GROUP_PRIMARY = "主色 Primary"
private const val GROUP_SECONDARY = "次色 Secondary"
private const val GROUP_TERTIARY = "第三色 Tertiary"
private const val GROUP_ERROR = "错误 Error"
private const val GROUP_SURFACE = "表面 Surface"
private const val GROUP_BACKGROUND = "背景 Background"
private const val GROUP_OUTLINE = "描边 Outline"
private const val GROUP_OTHER = "其他"

/**
 * M3 [ColorScheme] 的全部可引用槽位（共 48 个）。
 *
 * ⚠️ 本枚举存在的唯一目的：让用户在「颜色走廊」里**看到并选到**官方全部色槽，
 * 而不是每次都靠 AI 猜一个（历史教训：三处指示器全被猜成了 secondaryContainer，
 * 因为那是 `NavigationBarItemDefaults` 的默认值 —— 见 docs/UI设计规范.md §1.3.1）。
 *
 * [label] 用官方英文槽位名（= 代码里 `.colorScheme.xxx` 的后缀），因为用户要拿它对照代码；
 * [group] 只用于 UI 分组展示。
 */
enum class ColorSlot(val label: String, val group: String) {

    // ── 主色 Primary 系 ──
    Primary("primary", GROUP_PRIMARY),
    OnPrimary("onPrimary", GROUP_PRIMARY),
    PrimaryContainer("primaryContainer", GROUP_PRIMARY),
    OnPrimaryContainer("onPrimaryContainer", GROUP_PRIMARY),
    PrimaryFixed("primaryFixed", GROUP_PRIMARY),
    PrimaryFixedDim("primaryFixedDim", GROUP_PRIMARY),
    OnPrimaryFixed("onPrimaryFixed", GROUP_PRIMARY),
    OnPrimaryFixedVariant("onPrimaryFixedVariant", GROUP_PRIMARY),

    // ── 次色 Secondary 系 ──
    Secondary("secondary", GROUP_SECONDARY),
    OnSecondary("onSecondary", GROUP_SECONDARY),
    SecondaryContainer("secondaryContainer", GROUP_SECONDARY),
    OnSecondaryContainer("onSecondaryContainer", GROUP_SECONDARY),
    SecondaryFixed("secondaryFixed", GROUP_SECONDARY),
    SecondaryFixedDim("secondaryFixedDim", GROUP_SECONDARY),
    OnSecondaryFixed("onSecondaryFixed", GROUP_SECONDARY),
    OnSecondaryFixedVariant("onSecondaryFixedVariant", GROUP_SECONDARY),

    // ── 第三色 Tertiary 系（本项目长期闲置的一族，见规范 §1.3.1）──
    Tertiary("tertiary", GROUP_TERTIARY),
    OnTertiary("onTertiary", GROUP_TERTIARY),
    TertiaryContainer("tertiaryContainer", GROUP_TERTIARY),
    OnTertiaryContainer("onTertiaryContainer", GROUP_TERTIARY),
    TertiaryFixed("tertiaryFixed", GROUP_TERTIARY),
    TertiaryFixedDim("tertiaryFixedDim", GROUP_TERTIARY),
    OnTertiaryFixed("onTertiaryFixed", GROUP_TERTIARY),
    OnTertiaryFixedVariant("onTertiaryFixedVariant", GROUP_TERTIARY),

    // ── 错误 Error 系 ──
    Error("error", GROUP_ERROR),
    OnError("onError", GROUP_ERROR),
    ErrorContainer("errorContainer", GROUP_ERROR),
    OnErrorContainer("onErrorContainer", GROUP_ERROR),

    // ── 表面 Surface 系（卡片/底板的层级来源）──
    Surface("surface", GROUP_SURFACE),
    OnSurface("onSurface", GROUP_SURFACE),
    SurfaceVariant("surfaceVariant", GROUP_SURFACE),
    OnSurfaceVariant("onSurfaceVariant", GROUP_SURFACE),
    SurfaceTint("surfaceTint", GROUP_SURFACE),
    SurfaceDim("surfaceDim", GROUP_SURFACE),
    SurfaceBright("surfaceBright", GROUP_SURFACE),
    SurfaceContainerLowest("surfaceContainerLowest", GROUP_SURFACE),
    SurfaceContainerLow("surfaceContainerLow", GROUP_SURFACE),
    SurfaceContainer("surfaceContainer", GROUP_SURFACE),
    SurfaceContainerHigh("surfaceContainerHigh", GROUP_SURFACE),
    SurfaceContainerHighest("surfaceContainerHighest", GROUP_SURFACE),
    InverseSurface("inverseSurface", GROUP_SURFACE),
    InverseOnSurface("inverseOnSurface", GROUP_SURFACE),
    InversePrimary("inversePrimary", GROUP_SURFACE),

    // ── 背景 Background 系 ──
    Background("background", GROUP_BACKGROUND),
    OnBackground("onBackground", GROUP_BACKGROUND),

    // ── 描边 Outline 系 ──
    Outline("outline", GROUP_OUTLINE),
    OutlineVariant("outlineVariant", GROUP_OUTLINE),

    // ── 其他 ──
    Scrim("scrim", GROUP_OTHER);

    companion object {
        /** 分组顺序（UI 展示用，与 M3 官方文档的排列一致） */
        val GROUP_ORDER = listOf(
            GROUP_PRIMARY, GROUP_SECONDARY, GROUP_TERTIARY, GROUP_ERROR,
            GROUP_SURFACE, GROUP_BACKGROUND, GROUP_OUTLINE, GROUP_OTHER
        )

        /** 按分组归类，供走廊 UI 直接渲染 */
        fun byGroup(): List<Pair<String, List<ColorSlot>>> =
            GROUP_ORDER.map { g -> g to entries.filter { it.group == g } }
    }
}

/**
 * 槽位 → 实际颜色的取值。用 `when` 显式穷举而非反射，保证**编译期**就能发现
 * material3 升级后新增/删除槽位（反射写法会在运行时静默取到错色）。
 */
fun ColorScheme.colorOf(slot: ColorSlot): Color = when (slot) {
    ColorSlot.Primary -> primary
    ColorSlot.OnPrimary -> onPrimary
    ColorSlot.PrimaryContainer -> primaryContainer
    ColorSlot.OnPrimaryContainer -> onPrimaryContainer
    ColorSlot.PrimaryFixed -> primaryFixed
    ColorSlot.PrimaryFixedDim -> primaryFixedDim
    ColorSlot.OnPrimaryFixed -> onPrimaryFixed
    ColorSlot.OnPrimaryFixedVariant -> onPrimaryFixedVariant

    ColorSlot.Secondary -> secondary
    ColorSlot.OnSecondary -> onSecondary
    ColorSlot.SecondaryContainer -> secondaryContainer
    ColorSlot.OnSecondaryContainer -> onSecondaryContainer
    ColorSlot.SecondaryFixed -> secondaryFixed
    ColorSlot.SecondaryFixedDim -> secondaryFixedDim
    ColorSlot.OnSecondaryFixed -> onSecondaryFixed
    ColorSlot.OnSecondaryFixedVariant -> onSecondaryFixedVariant

    ColorSlot.Tertiary -> tertiary
    ColorSlot.OnTertiary -> onTertiary
    ColorSlot.TertiaryContainer -> tertiaryContainer
    ColorSlot.OnTertiaryContainer -> onTertiaryContainer
    ColorSlot.TertiaryFixed -> tertiaryFixed
    ColorSlot.TertiaryFixedDim -> tertiaryFixedDim
    ColorSlot.OnTertiaryFixed -> onTertiaryFixed
    ColorSlot.OnTertiaryFixedVariant -> onTertiaryFixedVariant

    ColorSlot.Error -> error
    ColorSlot.OnError -> onError
    ColorSlot.ErrorContainer -> errorContainer
    ColorSlot.OnErrorContainer -> onErrorContainer

    ColorSlot.Surface -> surface
    ColorSlot.OnSurface -> onSurface
    ColorSlot.SurfaceVariant -> surfaceVariant
    ColorSlot.OnSurfaceVariant -> onSurfaceVariant
    ColorSlot.SurfaceTint -> surfaceTint
    ColorSlot.SurfaceDim -> surfaceDim
    ColorSlot.SurfaceBright -> surfaceBright
    ColorSlot.SurfaceContainerLowest -> surfaceContainerLowest
    ColorSlot.SurfaceContainerLow -> surfaceContainerLow
    ColorSlot.SurfaceContainer -> surfaceContainer
    ColorSlot.SurfaceContainerHigh -> surfaceContainerHigh
    ColorSlot.SurfaceContainerHighest -> surfaceContainerHighest
    ColorSlot.InverseSurface -> inverseSurface
    ColorSlot.InverseOnSurface -> inverseOnSurface
    ColorSlot.InversePrimary -> inversePrimary

    ColorSlot.Background -> background
    ColorSlot.OnBackground -> onBackground

    ColorSlot.Outline -> outline
    ColorSlot.OutlineVariant -> outlineVariant

    ColorSlot.Scrim -> scrim
}

// ── 角色分组常量（同 ColorSlot，必须放顶层）──
private const val ROLE_GROUP_DOCK = "全局导航（底栏）"
private const val ROLE_GROUP_NAV = "页内导航（tab / rail）"
private const val ROLE_GROUP_CARD = "卡片"
private const val ROLE_GROUP_SURFACE = "表面层级（容器色唯一出口）"

/**
 * 可调「角色」—— 本项目 UI 里**实际会用到颜色**的语义位置。
 *
 * ⚠️ 每个角色的 [defaultSlot] / [defaultAlpha] **必须精确等于改动前代码里的硬编码值**，
 * 这样「未做任何覆盖」时全 App 观感与改动前逐像素一致（零回归）。
 * 一旦不符，说明走廊引入了视觉漂移 —— 这是本文件最容易被改错的地方。
 *
 * 角色划分依据 docs/UI设计规范.md §1.3.1 裁决表。
 */
enum class ColorRole(
    val label: String,
    val group: String,
    val defaultSlot: ColorSlot,
    val defaultAlpha: Float = 1f,
    val note: String = "",
    /**
     * 浅色模式专用槽位（null = 与 [defaultSlot] 相同）。
     *
     * ⚠️ 浅色与深色的 M3 色阶**阶梯方向相反**：深色 tone 越高越亮（Highest 最亮），
     *    浅色 tone 越低越深（Highest 最暗、离页面底最远）。故"同一档位"在两种模式下
     *    无法共用一个槽位时，用本字段单独指定浅色值，避免给整个角色写 `if (isDark)` 分支。
     * 覆盖优先级：颜色走廊的用户覆盖 > 本字段 > [defaultSlot]（见 [ColorRoleOverride.slotOf]）。
     */
    val lightSlot: ColorSlot? = null
) {
    // ── 全局导航（底栏）──
    // ⚠️ 2026-10 回归官方：此前 DockIndicator 用 primary@15%、DockContainer 用
    //    surfaceContainer，二者都是 InstallerX 的私有偏离（依据见 AppBottomBar.kt 头部注释）。
    //    现改回 M3 `NavigationBar` / `NavigationBarItemDefaults` 的官方默认值：
    //      - indicator = secondaryContainer（material3-1.5.0-alpha26 实跑确认，
    //        对 expressiveLightColorScheme 返回 #FFE8DEF8，与 secondaryContainer 逐位相等）
    //      - container = surface
    //    defaultAlpha 回到 1f：官方指示器是**不透明**色阶色，不是半透明混色。
    //    ⚠️ 若日后要再偏离，此处改回 Primary+0.15f 即可，但必须同时改 AppBottomBar.kt
    //    与 UI设计规范.md §1.3 的「唯一例外」条款，否则注释与代码再次脱节。
    DockIndicator(
        "底栏 · 指示器", ROLE_GROUP_DOCK, ColorSlot.SecondaryContainer, 1f,
        "选中项背后的胶囊。M3 官方默认值"
    ),
    DockActiveContent("底栏 · 选中图标/文字", ROLE_GROUP_DOCK, ColorSlot.OnSecondaryContainer),
    DockInactiveContent("底栏 · 未选中图标/文字", ROLE_GROUP_DOCK, ColorSlot.OnSurfaceVariant),
    DockContainer("底栏 · 胶囊底板", ROLE_GROUP_DOCK, ColorSlot.SurfaceContainer, 1f,
        "胶囊底板。M3 官方 NavigationBar 用的是 surfaceContainer，这里对齐官方。" +
            "⚠️ 2026-11 用户反馈「暗色模式下底栏发白」：此前是 surfaceContainerHigh（暗色 tone≈17），" +
            "比页面底（tone 4）亮出一大截，整条胶囊读起来像一块浅灰板。降一档到 surfaceContainer（tone≈12）。" +
            "若仍偏亮，下一步是 surfaceContainerLow（tone≈10），不要再往上加。"),

    // ── 页内导航 ──
    TabPill("页内 tab · 选中药丸", ROLE_GROUP_NAV, ColorSlot.SecondaryContainer),
    TabActiveContent("页内 tab · 选中文字", ROLE_GROUP_NAV, ColorSlot.OnSecondaryContainer),
    TabInactiveContent("页内 tab · 未选中文字", ROLE_GROUP_NAV, ColorSlot.OnSurfaceVariant),
    RailIndicator("仓库 rail · 指示器", ROLE_GROUP_NAV, ColorSlot.SecondaryContainer),
    RailActiveContent("仓库 rail · 选中文字", ROLE_GROUP_NAV, ColorSlot.OnSecondaryContainer),

    // ── 卡片 ──
    // ⚠️ 2026-10-22 用户反馈调档：主页卡片与页面底"太相近（略暗）"、
    //    设置页卡片又"凉了一点点"，要求取一个中间值。
    //
    // 先量化现状（material-kolor 5.0.0 字节码反查 + 实测，深海蓝种子 #FF2563EB，
    // TonalSpot / SPEC_2025 / PHONE；数值 = 卡片色与页面底 surface 的 WCAG 对比度）：
    //      surface              tone 4   #FF0D0E12   1.00  页面底
    //      surfaceContainerLow  tone 6   #FF121318   1.04  ← 主页原值：≈看不出
    //      surfaceContainer     tone 9   #FF181920   1.10  ← 新值：中间值
    //      surfaceContainerHigh tone 12  #FF1E1F26   1.18  ← 设置页原值：偏凉
    //      surfaceContainerHighest tone 15 #FF23252E 1.26
    //  深色下 Low→surface 的 ΔL* 仅约 0.6（无感知），High 的 1.18 又高过中间值一档，
    //  故 Low 与 High 都不合适，取 tone 9 的 surfaceContainer。
    //
    // 副作用（已核算，均为既有色阶的自然台阶，无手工调色）：
    //  - 主页网格小卡的图标芯片 HomeCardChip = surfaceContainerHighest（tone 15），
    //    与卡片面的台阶由 9 档收窄到 6 档，仍清晰可辨（对比 1.07→1.10）。
    //  - 浅色模式同步受益：Low→Container 对比 1.05→1.10，主页卡片不再糊在底上。
    //
    // 已知代价：不再等于 M3E `CardDefaults.cardColors()` 的官方默认（surfaceContainerLow）。
    // 本文件头部铁律「defaultSlot 必须等于改动前硬编码值、保证零回归」在此**被显意覆盖** ——
    // 用户明确要求改卡片明度，零回归原则让位于可感知的层级。改的是默认槽位这一个常量，
    // 全 App 卡片（主页/仓库/工具箱/颜色走廊）经 roleColor(CardSurface) 一处生效。
    // ⚠️ 2026-10-22 二次调档（SurfaceContainer → SurfaceContainerHighest）：
    //    上一版改到 SurfaceContainer（tone 9）后，实测与页面底 surface（tone 4）
    //    的对比仅 1.10，而 1.10 正好落在"人眼几乎看不出"的区间 ——
    //    用户反馈"卡片本身没有设计感、视觉上没有亮点"，根因就在这里。
    //
    //    本轮实测（material-kolor 5.0.0 直调，深海蓝种子 #FF2563EB，
    //    TonalSpot / SPEC_2025 / PHONE，对比基准 = 各自模式的 surface）：
    //      槽位                 深色 vs surface   浅色 vs surface
    //      SurfaceContainer        1.101             1.102   ← 上一版：两边都看不出
    //      SurfaceContainerHighest 1.264             1.222   ← 本轮：两边都明显
    //      SurfaceBright           1.385             1.000   ← ❌ 浅色下与底完全同色
    //
    //    为什么选 Highest 而不是更亮的 SurfaceBright：后者在浅色模式下是 tone 98，
    //    与 surface（tone 98）逐位相同，卡片会彻底消失 —— 必须给深浅两套槽位才能用，
    //    那等于维护两套配置。Highest 是**唯一一个单槽位、深浅两边都 ≥1.22 的灰色**，
    //    一个值一处改，不引入明暗分支。
    //
    //    副作用与配套：图标芯片 HomeCardChip / 胶囊 ToolItemPill 原本也用
    //    SurfaceContainerHighest，卡片面升到同档后会与它们同色而失去台阶，
    //    故这两个角色的默认槽一并下调到 SurfaceContainer（见各自定义处）。
    //
    // ⚠️ 2026-10-25 三次调档（仅浅色模式降一档：Highest → High）：
    //    用户反馈「主页/仓库/工具/设置/关于五页卡片颜色降一档（浅色模式下）」。
    //    深色下 Highest（tone 15，对比 1.264）观感认可，保持不动 —— 只调浅色。
    //    浅色槽位阶梯（SPEC_2025，tone）：Low 96 → Container 94 → High 92 → Highest 90，
    //    页面底 surface = 98。Highest 是离页面底最远的一档（对比 1.222），
    //    降一档到 High（tone 92，对比 1.163）即卡片明显变"轻"，不再像一块深灰压在白底上。
    //    ⚠️ 不能反过来改 defaultSlot：那会把深色也一起降档，违背用户"仅浅色"的限定。
    //
    //    配套（浅色同一档位差必须保住，否则小元素与卡片面糊在一起）：
    //      - HomeCardChip / ToolItemPill：Container(94) → Low(96)，与卡片面维持原来的 4 档台阶；
    //      - ToolSectionHeader：High(92) → Highest(90)，否则与卡片面**逐位同色**，
    //        工具页「常用工具」卡头那条色带会直接消失（它同时还兼职页面底上的分类标题/FLOW 胶囊底，
    //        取 Highest 后相对 surface 的对比 1.222，反而比改动前更清楚）。
    //
    // ⚠️ 2026-10-25 四次调档（浅色再降一档：High → Container，「看着脏脏的」）：
    //    用户反馈最新构建下浅色卡片「颜色有点深、看着脏脏的」，要求再降一档。
    //    根因不是单纯的明度：这条中性灰阶**tone 越低、彩度越高**，
    //    同一页面底(#FAF8FE, C*=5.01)下实测（material-kolor 5.0.0，TonalSpot / SPEC_2025）：
    //      tone 90 Highest   #E1E2ED   C*=8.52   vs 底 ΔE00=5.30
    //      tone 92 High      #E8E7F1   C*=7.55   vs 底 ΔE00=3.98   ← 三次调档后的值
    //      tone 94 Container #EEEDF6   C*=6.96   vs 底 ΔE00=2.66   ← 本轮
    //      tone 96 Low       #F4F3FA   C*=5.80   vs 底 ΔE00=1.21   ← 卡片会溶进页面底，不可用
    //    故「降一档」在此模式下等价于「提亮一档、同时降彩度」，方向正确。
    //    ⚠️ 认知纠偏（写下来防止下次再搞反）：浅色 tone 越高越亮（Lowest 100 最亮、
    //       Highest 90 最暗），与深色相反。深色链不动，仍 Highest（tone 15，对比 1.264）。
    //
    //    配套同步降一档（同一条灰阶链内整体下移，保持各元素相对卡片面的档位差不变）：
    //      - HomeCardChip / ToolItemPill：Low(96) → SurfaceBright(98)，与卡片面仍差 4 档。
    //        ⚠️ 浅色下 SurfaceBright 与 surface 逐位相同（都是 tone 98），
    //        即芯片底 = 页面底色，在卡片面上呈"镂空"效果，边界清晰（vs 卡片 ΔE00 2.6~6.2）。
    //      - ToolSectionHeader：保持 Highest(90)。卡片面降到 94 后，
    //        卡头(90) vs 卡身(94) 差 4 档（vs 卡片 ΔE00 约 2.7），比三次调档时的 1.5 更清楚。
    //    ⚠️ 仍不能改 defaultSlot：那会把深色一起降档，违背「仅浅色」的限定。
    CardSurface(
        "卡片 · 卡片面", ROLE_GROUP_CARD, ColorSlot.SurfaceContainerHighest,
        lightSlot = ColorSlot.SurfaceContainer
    ),
    // ⚠️ 2026-10-22 用户反馈「浅色模式下卡片图标背景填充饱和度太高，降一个档」，
    //    涉及工具页卡头 / 设置页 / 关于页 / 仓库页（都走 IconChip 这一角色，改一处全生效）。
    //
    //    实测（material-kolor 5.0.0，深海蓝种子 #FF2563EB，TonalSpot / SPEC_2025）：
    //      槽位                  浅色 tone/chroma        深色 tone/chroma
    //      PrimaryContainer      79.0 / 32.28           34.9 / 26.09   ← 改动前
    //      SecondaryContainer    89.9 / 16.03           25.1 / 15.67   ← 改动后
    //    SecondaryContainer 彩度恰为 PrimaryContainer 的一半，明度还高一档（tone 90 vs 79），
    //    视觉上就是"降一个档"：仍是同一色相族（次色与主色同源），不会换色相，
    //    但彩度减半后不再抢眼。深浅两模式同向受益。
    IconChip("卡片 · 图标芯片底", ROLE_GROUP_CARD, ColorSlot.SecondaryContainer),
    IconChipContent("卡片 · 图标芯片图标", ROLE_GROUP_CARD, ColorSlot.OnSecondaryContainer),
    // 2026-10 新增（主页两列网格小卡专用，与 IconChip 大卡芯片并存）：
    // 中性灰底（无色相，深浅模式都比卡片底 Low/High 有清晰台阶）+ primary 图标
    //（彩色点缀收回到图标本身）。多色图片 / 本地 SVG 放中性底上永远不撞色。
    // ⚠️ 2026-10-22：默认槽 SurfaceContainerHighest → SurfaceContainer。
    //    原因：CardSurface 本轮升到了 Highest，芯片若仍留在 Highest 就与卡片面同色，
    //    芯片边界消失、图标"浮"不起来。下调一档后，卡片亮、芯片暗，台阶反而更清楚。
    // ⚠️ 2026-10-25：浅色专用槽 SurfaceBright（tone 98）。浅色下卡片面已降到 Container(94)，
    //    芯片必须比卡片面更亮才有台阶。Low(96) 只差 2 档（ΔE00 1.2~2.7，44dp 方块几乎看不见），
    //    故整体再下移一档到 tone 98，与卡片面保持 4 档台阶（ΔL*≈4，与历次调档的相对关系一致）。
    //    ⚠️ 浅色下 SurfaceBright 与 surface 逐位相同，芯片底 = 页面底色，呈"镂空"效果。
    HomeCardChip(
        "主页网格卡 · 图标芯片底", ROLE_GROUP_CARD, ColorSlot.SurfaceContainer,
        lightSlot = ColorSlot.SurfaceBright
    ),
    HomeCardChipContent("主页网格卡 · 图标内容", ROLE_GROUP_CARD, ColorSlot.Primary),
    // 2026-10 新增（工具箱 ToolboxPage 密集网格工具项专用，与主页 HomeCardChip 语义同级但独立可调）：
    // 每个工具项做成「图标 + 文字」胶囊（CircleShape），为原本裸露的文字补上边界与层次。
    // 中性底 surfaceContainerHighest —— 依据规范 §1.3.1「网格密集小项中性底」裁决：工具箱几十个
    // 工具项平铺，铺彩底（primaryContainer/secondaryContainer）既会呈"灰紫斑"，又会把每个工具
    // 误读成"已选中"；中性底 + primary 图标深浅两模式台阶都清晰。文字 onSurface 与改动前文字色一致。
    // ⚠️ 2026-10-22：默认槽 SurfaceContainerHighest → SurfaceContainer。
    //    与 HomeCardChip 同理 —— CardSurface 升到 Highest 后，胶囊底必须下调才能拉开台阶。
    //    ⚠️ 注意工具页 FLOW 形态的胶囊**不在卡片里**（直接坐 surface 底上），
    //    此时 SurfaceContainer(tone 9) vs surface(tone 4) = 1.10 偏弱，
    //    故 FLOW 胶囊改用 ToolSectionHeader 同一档的 High（见 ToolboxTiles.kt）。
    // ⚠️ 2026-10-25：浅色专用槽 SurfaceBright（tone 98），理由同 HomeCardChip ——
    //    卡片面在浅色下降到 Container(94) 后，Low(96) 与它只差 2 档、胶囊边界会消失，
    //    故与芯片一起整体下移一档，保持 4 档台阶。
    ToolItemPill(
        "工具项 · 胶囊底", ROLE_GROUP_CARD, ColorSlot.SurfaceContainer,
        lightSlot = ColorSlot.SurfaceBright
    ),
    ToolItemPillContent("工具项 · 胶囊文字", ROLE_GROUP_CARD, ColorSlot.OnSurface),
    // 2026-10 新增（工具页分类标题条专用）：
    // 分类标题做成「40dp 圆形芯片 + 实心条」，条底需要一个与卡片面区分的颜色。
    // 默认取 SurfaceContainerHigh。⚠️ 实测提醒：CardSurface 本轮已升到 Highest，
    // 而标题条走 High，两者只差一档（深色 1.175 → 1.264，相邻台阶约 1.075）。
    // 这个差值**不足以单独承担"分区"的区分职责**，故标题条同时靠
    // 40dp 圆形芯片 + titleLarge(20sp) 字号 + extraLarge 圆角形态来分层（形状/字号不占色阶额度）。
    // 若真机上仍显得糊，走廊里把它换到有色相的槽位（如 SecondaryContainer）即可。
    // ⚠️ 为什么不直接写 colorScheme：那样用户在颜色走廊里找不到这一项、调不了它；
    //    接成角色后可以单独把标题条换到任意槽位（规范 §1.3.2 契约）。
    // ⚠️ 2026-10-25：浅色专用槽 Highest（tone 90）。浅色卡片面三次调档降到 High(92) 后，
    //    标题条若仍留 High 就与卡片面**逐位同色**，「常用工具」卡头色带会消失，故取 Highest。
    // ⚠️ 2026-11 五次调档（浅色 Highest(90) → Container(94)）：
    //    用户反馈「工具页卡片颜色都深，深色模式正常，浅色模式下看起来很脏」。
    //
    //    根因是这条灰阶**没有跟上前四次调档**：卡片面从 Highest(90) 一路降到
    //    Container(94)，而本槽位停在 Highest(90) —— 卡头与卡身的色差不但没被
    //    "保 4 档"，反而从最初的 1 档被拉大到 4 档（ΔE00 2.7）。
    //    又因为本角色在浅色下被三处复用（卡头 / 折叠标题条 / FLOW 胶囊底），
    //    整页几乎每个工具胶囊、每条标题都压着 tone 90 的深灰 → 用户看到的
    //    就是"工具页整体发脏"。深色模式不受影响（defaultSlot 未动）。
    //
    //    修法：浅色槽位改为与卡片面**同档**(Container 94)，靠形状/字号分层
    //    （下面注释里早就写明"形状/字号不占色阶额度"），不再用色阶硬拉。
    //
    //    ⚠️ 不能改成 Low(96)/SurfaceBright(98)：那样会与页面底 surface(98)
    //       糊在一起，FLOW 胶囊（直接坐页面上）会彻底消失。
    //       Container(94) 相对 surface(98) 仍有 ΔE00≈2.66 的可见边界，
    //       且在卡片内与卡身同色 → 用户抱怨的"深色块"消失。
    ToolSectionHeader(
        "工具页 · 分类标题条底", ROLE_GROUP_CARD, ColorSlot.SurfaceContainerHigh,
        lightSlot = ColorSlot.SurfaceContainer
    ),

    // ══════════════════════ 表面层级（全 App 唯一出口）══════════════════════
    //
    // ⚠️ 2026-11 新增。为什么必须收口到这里 —— 一次实测：
    //    全项目直接写 `MaterialTheme.colorScheme.surfaceContainer*` 共 **80 处**，
    //    经「颜色走廊」的 roleColor 只有 **55 处**，而走廊原本只覆盖 **15 个角色**。
    //    结果是：**只有 5 个大页面和底栏能被走廊调色，其余 10 个页面各写各的色阶**。
    //    这就是用户说的「东拼西凑」——不是某个颜色错，而是**没有单一真相源**。
    //
    //    最能说明问题的是「卡片」：五页走 `CardSurface`，而
    //    Antutu 5 个卡片文件 / 榜单 / 任务中心 / 播放器 / 通用列表 / 媒体详情
    //    全部直接写 `surfaceContainerLow`。同一屏上出现两种"卡片色"，
    //    用户看不到代码，只会说"颜色很怪、色阶有的暗有的亮"。
    //
    // 本节把「表面层级」这一件事收成唯一出口：任何容器色都从下面三个角色取，
    // 而不是从 5 档 surface 里随手挑。差别是：这四档有**语义**（层级），
    // 5 档 surface 只有**tone 数字**——没有语义的色阶，每换一次页面就换一个数字，
    // 永远不会收敛。

    /** 页面底 / 内容底板：顶栏、输入框、整屏背景。**L0** */
    SurfacePage("表面层 · 页面底", ROLE_GROUP_SURFACE, ColorSlot.Surface),
    /** 层级切换带：代码块、列表条带、次级面板。**L2** */
    SurfaceStrip("表面层 · 条带/次级面板", ROLE_GROUP_SURFACE, ColorSlot.SurfaceContainerHigh),
    /** 页面底上的小元素芯片：缩略图托底、图标底、进度条轨道。**L3** */
    SurfaceChip("表面层 · 小元素托底", ROLE_GROUP_SURFACE, ColorSlot.SurfaceContainerHighest);

    companion object {
        val GROUP_ORDER = listOf(ROLE_GROUP_DOCK, ROLE_GROUP_NAV, ROLE_GROUP_CARD, ROLE_GROUP_SURFACE)

        fun byGroup(): List<Pair<String, List<ColorRole>>> =
            GROUP_ORDER.map { g -> g to entries.filter { it.group == g } }
    }
}
