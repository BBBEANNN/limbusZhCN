package com.example.limbuszhcn.container

/**
 * 统一管理可进入运行时汉化索引的显示文本字段与文本质量规则。
 *
 * 编译器和补丁收集器共用该策略，避免一端已收集字段、另一端却没有生成术语或索引。
 * native 运行时保留同名白名单，用于反射处理 `TextData_*` 对象字段。
 */
internal object TranslationTextPolicy {
    /**
     * 经截图与日文原文共同确认、可安全跨页面复用的短语覆盖。
     *
     * 这些条目优先于汉化包派生出的短术语，专门修复完整句未命中后只替换汉字、
     * 却把日文助词留在界面上的情况。这里只允许加入已核对过的确定短语。
     */
    val BUILT_IN_TERM_ENTRIES: Map<String, String> = linkedMapOf(
        "破壊されずに命中時" to "未被破坏且命中时",
        "破坏されずに命中时" to "未被破坏且命中时"
    )

    /**
     * 汉化包中已确认的错译片段及其统一译名。
     *
     * 使用有序映射保证编译结果和输入摘要稳定，也避免在 native 层维护第二份纠错表。
     */
    private val KNOWN_TRANSLATION_CORRECTIONS: Map<String, String> = linkedMapOf(
        "喘息未定" to "呼吸法",
        "破壊されずに命中時" to "未被破坏且命中时",
        "破坏されずに命中时" to "未被破坏且命中时"
    )

    /**
     * 已确认只承载玩家可见文本的 JSON 字段。
     *
     * 标识符、模型、语音、图标和颜色等元数据不得加入该集合。
     */
    val DISPLAY_FIELDS: Set<String> = setOf(
        "content", "dialog", "dlg", "teller",
        "name", "nameWithTitle", "nickName", "longName", "specialName",
        "shortName", "abName", "abnormalityName", "panicName", "skinItemTitle",
        "desc", "description", "simpleDesc", "summary", "flavor", "rawDesc", "behaveDesc",
        "eventDesc", "prevDesc", "subDesc", "successDesc", "failureDesc",
        "message", "messageDesc", "result", "panicDescription",
        "lowMoraleDescription", "skinItemDesc", "acquisitionMethod",
        "title", "oneLineTitle", "place", "codeName", "clue", "sentence",
        "text", "subText", "mainText", "story",
        "openCondition", "openConditionNumber", "relatedChapterText", "askLevelUp",
        "company", "area", "chapter", "chapterNumber", "chaptertitle", "parttitle",
        "timeline", "teacher", "add", "min", "variation", "variation2"
    )

    /**
     * 允许生成上下文无关短术语的字段。
     *
     * 剧情和长描述只能走完整文本映射，避免其中偶然出现的短句污染其他页面。
     */
    private val TERM_FIELDS: Set<String> = setOf(
        "content", "name", "shortName", "abName", "title", "summary"
    )

    /**
     * 判断字段能否作为玩家可见文本进入运行时索引。
     *
     * @param field JSON 字段名。
     * @return 字段已通过显式显示文本审查时返回 `true`。
     */
    fun isDisplayField(field: String): Boolean = field in DISPLAY_FIELDS

    /**
     * 判断字段是否适合派生可跨页面使用的短术语。
     *
     * @param field JSON 字段名。
     * @return 字段同时属于显示文本与受控术语来源时返回 `true`。
     */
    fun isTermField(field: String): Boolean = field in TERM_FIELDS && isDisplayField(field)

    /**
     * 对已人工核对的上游错译和半日文片段做确定性纠正。
     *
     * @param value 汉化包提供的原始中文译文。
     * @return 仅替换已登记片段后的译文；其余内容和富文本标签保持不变。
     */
    fun correctKnownTranslation(value: String): String =
        KNOWN_TRANSLATION_CORRECTIONS.entries.fold(value) { corrected, (source, target) ->
            corrected.replace(source, target)
        }

    /**
     * 判断文本是否不存在可确定的编码损坏。
     *
     * 这里只拦截 Unicode 替换字符、DEL/C1 与除换行、回车、制表符之外的 C0 控制字符。
     * 不根据自然语言内容猜测错译，避免把剧情中的符号、箭头或故意乱码误删。
     *
     * @param value 待写入索引的日文原文或中文译文。
     * @return 文本可安全转换为 IL2CPP UTF-16 字符串时返回 `true`。
     */
    fun isUsableDisplayText(value: String): Boolean = value.none { character ->
        character == '\uFFFD' ||
            (character.code < 0x20 && character != '\n' && character != '\r' && character != '\t') ||
            character.code in 0x7F..0x9F
    }
}
