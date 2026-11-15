package com.xixin.apkforge.core.dex.patch

/**
 * dex 文件格式的常量与结构偏移。
 *
 * 【为什么把这些集中在一个文件】增量写入靠「在固定偏移上读写固定宽度的字段」，
 * 一旦某个偏移写错，产物不是报错，而是**装不上**——错误信息在 ART 那边，
 * 与本工程的代码毫无关系。所以偏移只允许出现在这里，且每条都注明依据
 * （Android 源码 `art/libdexfile/dex/dex_file.h` 与 `dex_file_verifier.cc`）。
 *
 * 【与 dexlib2 的分工】本包只做「读原始字节 + 写原始字节」，
 * 不解析指令语义。指令处理仍由 dexlib2 完成（它读得对、写得慢），
 * 我们只借用它的**读**能力，写由自己控制。
 */
internal object DexFormat {

    /** header_item 的固定长度。 */
    const val HEADER_SIZE = 0x70

    /** 各类 id 区 / class_def_item 的固定宽度。 */
    const val CLASS_DEF_WIDTH = 32
    const val CODE_ITEM_HEADER = 16

    // ────────────────────── header_item 字段偏移 ──────────────────────
    /** `dex\n035\0` 之类，8 字节 */
    const val H_MAGIC = 0x00
    /** 文件总长度（u32） */
    const val H_FILE_SIZE = 0x20
    /** 头部长度，恒 0x70（u32） */
    const val H_HEADER_SIZE = 0x24
    /** 0x12345678（u32） */
    const val H_ENDIAN_TAG = 0x28
    /** map_list 的偏移（u32） */
    const val H_MAP_OFF = 0x34
    /** string_ids 项数（u32） */
    const val H_STRING_IDS_SIZE = 0x38
    const val H_STRING_IDS_OFF = 0x3C
    /** type_ids 项数 / 偏移（u32） */
    const val H_TYPE_IDS_SIZE = 0x40
    const val H_TYPE_IDS_OFF = 0x44
    /** proto_ids */
    const val H_PROTO_IDS_SIZE = 0x48
    const val H_PROTO_IDS_OFF = 0x4C
    /** field_ids */
    const val H_FIELD_IDS_SIZE = 0x50
    const val H_FIELD_IDS_OFF = 0x54
    /** method_ids */
    const val H_METHOD_IDS_SIZE = 0x58
    const val H_METHOD_IDS_OFF = 0x5C
    /** class_defs 项数 / 偏移（u32） */
    const val H_CLASS_DEFS_SIZE = 0x60
    const val H_CLASS_DEFS_OFF = 0x64
    /** 数据区大小 / 起始偏移（u32） */
    const val H_DATA_SIZE = 0x68
    const val H_DATA_OFF = 0x6C

    // ────────────────────── class_def_item 字段偏移（相对项起点）──────────────────────
    /** 所属类描述符在 type_ids 里的下标（u32） */
    const val CD_CLASS_IDX = 0
    /** 访问标志（u32） */
    const val CD_ACCESS_FLAGS = 4
    /** 父类 type_ids 下标（u32，NO_INDEX 表示无） */
    const val CD_SUPERCLASS_IDX = 8
    /** 接口列表偏移（u32，0 表示无） */
    const val CD_INTERFACES_OFF = 12
    /** 源文件名在 string_ids 里的下标（u32） */
    const val CD_SOURCE_FILE_IDX = 16
    /** 注解目录偏移（u32，0 表示无） */
    const val CD_ANNOTATIONS_OFF = 20
    /** **class_data_item 的偏移（u32，0 表示无）** ← 增量写入改的就是这 4 字节 */
    const val CD_CLASS_DATA_OFF = 24
    /** 静态字段初始值数组偏移（u32，0 表示无） */
    const val CD_STATIC_VALUES_OFF = 28

    // ────────────────────── code_item 布局 ──────────────────────
    /** registers_size (u16) */
    const val CI_REGISTERS_SIZE = 0
    /** ins_size (u16) */
    const val CI_INS_SIZE = 2
    /** outs_size (u16) */
    const val CI_OUTS_SIZE = 4
    /** tries_size (u16) */
    const val CI_TRIES_SIZE = 6
    /** debug_info_off (u32) */
    const val CI_DEBUG_INFO_OFF = 8
    /** insns_size (u32)，单位是 16 位指令字 */
    const val CI_INSNS_SIZE = 12
    /** 指令字节从此开始 */
    const val CI_INSNS = 16

    // ────────────────────── map_list 类型码 ──────────────────────
    /**
     * `map_list` 的 type 码。
     *
     * 【为什么需要全部】`map_list` 是 ART 用来快速定位各段的结构：每项记录
     * 「这一类的数据项有几个、从哪个偏移开始」。池注入把 6 个索引段全搬到了
     * 文件末尾，所以**每个对应项的 offset 都要改**；新增条目还要改 size。
     * 漏改任何一项，ART 的校验器会认为 map 与实际不符而拒收——
     * 而这种错在构建期完全不报错，只在装机时炸。
     */
    const val TYPE_MAP_LIST = 0x1000
    const val TYPE_HEADER_ITEM = 0x0000
    const val TYPE_STRING_ID_ITEM = 0x0001
    const val TYPE_TYPE_ID_ITEM = 0x0002
    const val TYPE_PROTO_ID_ITEM = 0x0003
    const val TYPE_FIELD_ID_ITEM = 0x0004
    const val TYPE_METHOD_ID_ITEM = 0x0005
    const val TYPE_CLASS_DEF_ITEM = 0x0006
    const val TYPE_CALL_SITE_ID_ITEM = 0x0007
    const val TYPE_METHOD_HANDLE_ITEM = 0x0008
    const val TYPE_STRING_DATA_ITEM = 0x2002
    const val TYPE_TYPE_LIST = 0x1001
    const val TYPE_ANNOTATION_SET_REF_LIST = 0x1002
    const val TYPE_ANNOTATION_SET_ITEM = 0x1003
    const val TYPE_CLASS_DATA_ITEM = 0x2000
    const val TYPE_CODE_ITEM = 0x2001
    const val TYPE_DEBUG_INFO_ITEM = 0x2003
    const val TYPE_ANNOTATION_ITEM = 0x2004
    const val TYPE_ENCODED_ARRAY_ITEM = 0x2005
    const val TYPE_ANNOTATIONS_DIRECTORY_ITEM = 0x2006
    const val TYPE_HIDDENAPI_CLASS_DATA_ITEM = 0xF000
    /** 每种 map item 的固定宽度：type(u16) + unused(u16) + size(u32) + offset(u32) */
    const val MAP_ITEM_WIDTH = 12

    /** 表示「不存在」的下标值。 */
    const val NO_INDEX = 0xFFFFFFFFL

    /** 魔数首 4 字节 `dex\n`。 */
    val MAGIC_PREFIX = byteArrayOf(0x64, 0x65, 0x78, 0x0A)

    /** 所有区段的 4 字节对齐要求（dex 规范：data 区内的项按类型有对齐要求）。 */
    const val ALIGN_4 = 4
}
