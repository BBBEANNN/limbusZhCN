package com.example.limbuszhcn.container

/**
 * 统一管理可进入运行时汉化索引的显示文本字段与文本质量规则。
 *
 * 编译器和补丁收集器共用该策略，避免一端已收集字段、另一端却没有生成术语或索引。
 * native 运行时保留同名白名单，用于反射处理 `TextData_*` 对象字段。
 */
internal object TranslationTextPolicy {
    /**
     * 已确认只承载玩家可见文本的 JSON 字段。
     *
     * 标识符、模型、语音、图标和颜色等元数据不得加入该集合。
     */
    val DISPLAY_FIELDS: Set<String> = setOf(
        "content", "dialog", "dlg", "teller", "speaker", "displayName", "statText",
        "goalDescription1", "goalDescription2", "goalDescription3", "goalDescription4",
        "goalDescription5", "goalDescription6", "goalDescription7", "goalDescription8",
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
     * 允许从逐行结构中派生“命中时、使用时”等前置条件的说明字段。
     *
     * 派生结果仍来自同一条日中配对记录，不维护独立译名表。
     */
    private val STRUCTURED_DESCRIPTION_FIELDS: Set<String> = setOf(
        "desc", "description", "simpleDesc", "rawDesc", "behaveDesc",
        "eventDesc", "subDesc", "successDesc", "failureDesc"
    )

    /** 这些目录承载对白、歌词或语音正文，其中的短句只保留完整文本映射。 */
    private val NARRATIVE_DIRECTORIES: Set<String> = setOf(
        "storydata", "bgmlyrics", "personalityvoicedlg", "egovoicedig",
        "battleannouncerdlg", "battlespeechbubbledlg"
    )

    /** 同类资源也可能位于语言根目录；按资源族识别，避免误伤 StoryDungeonUI 等界面表。 */
    private val NARRATIVE_FILE_PREFIXES: Set<String> = setOf(
        "bgmlyrics_", "voice_", "egovoice", "personalityvoicedlg",
        "battleannouncerdlg", "battlespeechbubbledlg"
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
     * 判断字段及其资源来源是否允许派生跨页面的短术语。
     *
     * 剧情、歌词和语音也使用 `content`，但其中的“子。”等对白不能当成通用术语，
     * 否则会改写其他译文中的同形片段。限制只影响短术语，完整文本仍正常收集。
     *
     * @param path 日文源资源的相对或绝对路径，兼容正斜杠和反斜杠。
     * @param field 已收集的显示字段名。
     * @return 字段适合短术语且不属于叙述正文时返回 `true`。
     */
    fun isTermSource(path: String, field: String): Boolean {
        if (!isTermField(field)) return false
        if (field != "content") return true
        val segments = path.replace('\\', '/').lowercase().split('/')
        val narrativeDirectory = segments.dropLast(1).any { it in NARRATIVE_DIRECTORIES }
        val fileName = segments.last().removePrefix("jp_")
        return !narrativeDirectory && NARRATIVE_FILE_PREFIXES.none { prefix -> fileName.startsWith(prefix) }
    }

    /**
     * 判断字段是否适合按相同行号派生格式化后的前置条件术语。
     *
     * @param field JSON 字段名。
     * @return 字段属于结构化说明文本时返回 `true`。
     */
    fun isStructuredDescriptionField(field: String): Boolean =
        field in STRUCTURED_DESCRIPTION_FIELDS && isDisplayField(field)

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
