package com.example.limbuszhcn.container

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 配对同一资源表的显示字段；有稳定标识时禁止回退到另一条记录的位置。 */
internal class TranslationRecordCollector(private val audit: Boolean = false) {
    fun collect(path: String, originalJson: String, translationJson: String): List<TranslationIndexRecord> =
        buildList {
            walk(JSONTokener(originalJson).nextValue(), JSONTokener(translationJson).nextValue(),
                path, "$", null, this)
        }

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
                        val display = TranslationTextPolicy.isDisplayField(field)
                        val unreviewed = audit && !display && source.any { it in '\u3040'..'\u30ff' } && source != target
                        if ((display && (target is String || audit)) || unreviewed) {
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
                        candidates?.takeIf { it.size == sourceCounts[sourceId] }?.getOrNull(occurrence)
                    } else translated?.opt(index)
                    when {
                        source is JSONObject || source is JSONArray ->
                            if (target != null || audit) walk(source, target, path, "$context[$index]", parentField, output)
                        source is String && parentField != null && TranslationTextPolicy.isDisplayField(parentField) ->
                            if (target is String || audit) output += TranslationIndexRecord(
                                path, "$context[$index]", parentField, source, target as? String ?: "")
                    }
                }
            }
        }
    }

    private fun identity(value: JSONObject): String? {
        for (field in ALIGNMENT_FIELDS) {
            val id = value.opt(field)
            if (id is String || id is Number) return "$field=$id"
        }
        return null
    }

    private companion object {
        val ALIGNMENT_FIELDS = listOf("id", "level", "index", "idx", "originalId", "key")
    }
}
