package com.example.limbuszhcn.container

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 配对同一资源表的显示字段；有稳定标识时禁止回退到另一条记录的位置。
 *
 * @param audit 是否同时记录缺少配对的显示字段和待审查的含假名字段。
 */
internal class TranslationRecordCollector(private val audit: Boolean = false) {
    /**
     * 在同一资源内部递归收集有结构依据的日中显示文本配对。
     *
     * @param path 用于索引来源和覆盖审计的资源路径。
     * @param originalJson 设备可读的日文原始 JSON。
     * @param translationJson 当前汉化包提供的对应 JSON。
     * @return 可确定对应关系的字段；审计模式还会保留未配对源字段。
     */
    fun collect(path: String, originalJson: String, translationJson: String): List<TranslationIndexRecord> =
        buildList {
            walk(JSONTokener(originalJson).nextValue(), JSONTokener(translationJson).nextValue(),
                path, "$", null, this)
        }

    /**
     * 沿原始结构遍历，避免译文新增或重排条目时跨记录借用显示字段。
     *
     * @param original 当前原始对象、数组或值。
     * @param translation 已确定属于当前原始分支的译文，无法配对时为空。
     * @param path 原始资源路径。
     * @param context 当前分支的数组位置和稳定标识上下文。
     * @param parentField 数组所属字段，用于审核字符串数组的显示用途。
     * @param output 接收已配对文本或审计缺项的结果列表。
     */
    private fun walk(
        original: Any?, translation: Any?, path: String, context: String,
        parentField: String?, output: MutableList<TranslationIndexRecord>
    ) {
        when (original) {
            is JSONObject -> {
                val translated = translation as? JSONObject
                val objectContext = identity(original)?.let { "$context[$it]" } ?: context
                val keys = original.keys()
                while (keys.hasNext()) {
                    val field = keys.next()
                    val source = original.opt(field)
                    val target = translated?.opt(field)
                    if (source is String) {
                        if (shouldCollect(field, source, target)) {
                            output += TranslationIndexRecord(path, objectContext, field, source, target as? String ?: "")
                        }
                    } else if (source is JSONObject || source is JSONArray) {
                        if (target is JSONObject || target is JSONArray || audit) {
                            walk(source, target, path, "$objectContext.$field", field, output)
                        }
                    }
                }
            }
            is JSONArray -> {
                val translated = translation as? JSONArray
                val byId = linkedMapOf<String, MutableList<JSONObject>>()
                for (index in 0 until (translated?.length() ?: 0)) {
                    val item = translated?.optJSONObject(index) ?: continue
                    identity(item)?.let { id -> byId.getOrPut(id) { mutableListOf() } += item }
                }
                val sourceCounts = (0 until original.length()).mapNotNull {
                    original.optJSONObject(it)?.let(::identity)
                }.groupingBy { it }.eachCount()
                // 父数组只要有一侧使用稳定标识，无标识条目就不能借用当前位置。
                // 双方完全没有标识时也必须等长，否则插入/删除一项会让后续文本整体错位。
                val canPairByPosition = translated != null &&
                    original.length() == translated.length() && sourceCounts.isEmpty() && byId.isEmpty()
                val occurrences = mutableMapOf<String, Int>()
                for (index in 0 until original.length()) {
                    val source = original.opt(index)
                    val sourceId = (source as? JSONObject)?.let(::identity)
                    val target = if (sourceId != null) {
                        val occurrence = occurrences[sourceId] ?: 0
                        occurrences[sourceId] = occurrence + 1
                        val candidates = byId[sourceId]
                        // Some story IDs (especially -1) intentionally repeat. Pair their local
                        // occurrence only when both sides contain the same number of that ID.
                        // 重复标识仍按同组次序配对；数量变化时保留缺项，不能猜测被删掉的位置。
                        candidates?.takeIf { it.size == sourceCounts[sourceId] }?.getOrNull(occurrence)
                    } else if (canPairByPosition) translated?.opt(index) else null
                    when {
                        source is JSONObject || source is JSONArray ->
                            if (target != null || audit) walk(source, target, path, "$context[$index]", parentField, output)
                        source is String && parentField != null && shouldCollect(parentField, source, target) ->
                            output += TranslationIndexRecord(
                                path, "$context[$index]", parentField, source, target as? String ?: "")
                    }
                }
            }
        }
    }

    /**
     * 共用对象字段和字符串数组的收集策略，防止新增数组显示字段在覆盖审计中漏报。
     *
     * @param field 字符串所属字段名。
     * @param source 原始字符串。
     * @param target 当前结构配对取得的目标值，缺项时为空。
     * @return 字段允许进入生产索引，或需要作为未知字段进入开发审计时返回 `true`。
     */
    private fun shouldCollect(field: String, source: String, target: Any?): Boolean {
        val display = TranslationTextPolicy.isDisplayField(field)
        // 未审核的含假名字段只供审计定位，不能因为发现日文就自动加入生产白名单。
        val unreviewed = audit && !display && source.any { it in '\u3040'..'\u30ff' } && source != target
        return (display && (target is String || audit)) || unreviewed
    }

    /**
     * 按约定优先级取得当前记录的首个稳定标识。
     *
     * @param value 需要在同一父数组中匹配的对象。
     * @return 包含字段名的标识，避免不同标识字段同值时互相误配；无标识时为空。
     */
    private fun identity(value: JSONObject): String? {
        for (field in ALIGNMENT_FIELDS) {
            val id = value.opt(field)
            if (id is String || id is Number) return "$field=$id"
        }
        return null
    }

    private companion object {
        /** 只使用明确的表内标识，不把显示字段或跨资源裸 ID 当作匹配依据。 */
        val ALIGNMENT_FIELDS = listOf("id", "level", "index", "idx", "originalId", "key")
    }
}
