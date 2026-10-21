#if defined(__ANDROID__)
// Android tests compile the production matcher as well. Unused hook entry points
// are discarded by the linker; this executable never loads or hooks the game.
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTranslationRuntime.cpp"
#else
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTextLayoutPolicy.h"
#endif
#include <cstdio>
#include <limits>
#include <memory>

#if defined(__ANDROID__)
struct ProbeString { int32_t length = 0; std::vector<uint16_t> chars; };
static std::vector<std::unique_ptr<ProbeString>> probe_strings;
// 测试用弱句柄模拟真实 IL2CPP 所有权；句柄失效和新对象同文必须分别验证。
static std::unordered_map<uintptr_t, void *> probe_handles;
static uintptr_t next_probe_handle = 1;
static uintptr_t probe_weakref(void *value, bool) {
    uintptr_t handle = next_probe_handle++;
    probe_handles[handle] = value;
    return handle;
}
static void *probe_target(uintptr_t handle) {
    auto found = probe_handles.find(handle);
    return found == probe_handles.end() ? nullptr : found->second;
}
static void probe_free(uintptr_t handle) { probe_handles.erase(handle); }
static int32_t probe_length(void *value) {
    return static_cast<ProbeString *>(value)->length;
}
static const uint16_t *probe_chars(void *value) {
    return static_cast<ProbeString *>(value)->chars.data();
}
static void *probe_new(const uint16_t *chars, int32_t length) {
    auto value = std::make_unique<ProbeString>();
    value->length = length;
    if (length > 0) value->chars.assign(chars, chars + length);
    value->chars.push_back(0);
    void *result = value.get();
    probe_strings.push_back(std::move(value));
    return result;
}

/** 初始化独立程序的字符串/弱句柄适配器，不加载或修改游戏进程。 */
static void prepare_probe_runtime() {
    g_string_length = probe_length;
    g_string_chars = probe_chars;
    g_string_new_utf16 = probe_new;
    g_gchandle_new_weakref = probe_weakref;
    g_gchandle_get_target = probe_target;
    g_gchandle_free = probe_free;
}

/** 从可读测试文本构建托管字符串替身；编码失败由后续断言检测。 */
static void *probe_utf8(const std::string &text) {
    std::u16string chars;
    if (!utf8_to_utf16(text, &chars)) return nullptr;
    return probe_new(reinterpret_cast<const uint16_t *>(chars.data()), static_cast<int32_t>(chars.size()));
}

/** 读取替身内容，仅供断言，不把资源正文写入审计报告。 */
static std::string probe_text(void *value) {
    std::string text;
    if (value != nullptr) utf16_to_utf8(probe_chars(value), probe_length(value), &text);
    return text;
}

/** 每条审计独立释放来源凭据，避免前一行译文影响下一行全新日文对象的判断。 */
static void reset_probe_origins() {
    clear_translation_owned_strings();
    g_translated_managed_texts.clear();
    probe_strings.clear();
}

// Standalone audit of the exact production lookup pipeline. No Unity or game
// process is loaded, and only source hashes/results are written to the report.
static int audit_lookup(const char *input_path, const char *index_path) {
    if (!load_index_file(index_path)) return 2;
    FILE *input = fopen(input_path, "rb");
    if (input == nullptr) return 2;
    FILE *output = fopen("/data/local/tmp/limbus-translation-audit.tsv", "wb");
    if (output == nullptr) { fclose(input); return 2; }
    fprintf(output, "sourceHash64\tresult\tstablePasses\tstableFormattedCopy\n");
    prepare_probe_runtime();
    uint32_t count = 0;
    bool valid = read_u32(input, &count) && count <= kMaxEntries;
    size_t expected = 0, unchanged = 0, different = 0, unpaired = 0, unstable = 0;
    for (uint32_t i = 0; valid && i < count; ++i) {
        std::string hash, source, translation;
        valid = read_string(input, &hash) && read_string(input, &source) && read_string(input, &translation);
        if (!valid) break;
        reset_probe_origins();
        std::u16string chars;
        valid = utf8_to_utf16(source, &chars);
        if (!valid) break;
        void *original = probe_new(reinterpret_cast<const uint16_t *>(chars.data()),
                                   static_cast<int32_t>(chars.size()));
        void *result = translate_managed_string(original);
        std::string actual;
        valid = utf16_to_utf8(probe_chars(result), probe_length(result), &actual);
        const char *status;
        if (!translation.empty() && actual == translation) { status = "matches_pair"; ++expected; }
        else if (actual == source) { status = "unchanged"; ++unchanged; }
        else if (translation.empty()) { status = "translated_without_pair"; ++unpaired; }
        else { status = "different_from_pair"; ++different; }
        // 核对同一结果跨三层取得路径和已知模板内容复制后的稳定性，不只检查单词自扩展。
        void *second = translate_managed_string(result);
        void *third = translate_managed_string(second);
        bool stable_passes = probe_text(second) == actual && probe_text(third) == actual;
        bool stable_copy = true;
        if (is_translation_owned_string(result)) {
            std::vector<std::string> templates{actual};
            stable_copy = probe_text(translate_managed_string(probe_utf8(actual), &templates)) == actual;
        }
        if (!stable_passes || !stable_copy) ++unstable;
        fprintf(output, "%s\t%s\t%d\t%d\n", hash.c_str(), status, stable_passes, stable_copy);
        reset_probe_origins();
    }
    fclose(input);
    fclose(output);
    printf("Native audit rows=%u matches=%zu unchanged=%zu different=%zu translated_without_pair=%zu unstable=%zu valid=%d\n",
           count, expected, unchanged, different, unpaired, unstable, valid);
    return valid && unstable == 0 ? 0 : 2;
}
#endif

static int failures = 0;
static void check(bool condition, const char *message) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        ++failures;
    }
}

int main(int argc, char **argv) {
    using namespace limbus_text_layout;
    // A reused button may report a rendered 17.6 even though its configured size is 32.
    check(configured_font_size(17.6f, true, true, 32.0f) == 32.0f,
          "reused auto-sized button recovers configured size");
    check(configured_font_size(35.0f, false, true, 72.0f) == 35.0f,
          "fixed-size controls retain their configured size");
    check(configured_font_size(32.0f, true, false, 0.0f) == 32.0f,
          "unavailable maximum keeps existing size");
    check(configured_font_size(32.0f, true, true, std::numeric_limits<float>::infinity()) == 32.0f,
          "invalid maximum cannot poison layout");
    check(has_explicit_break("first\r\nsecond"), "package CRLF is a break");
    check(has_explicit_break("first<br>second"), "TMP br is a break");
    check(has_explicit_break("first<br/>second"), "TMP self-closing br is a break");
    check(has_explicit_break("first<BR />second"), "uppercase TMP br is a break");
    check(!has_explicit_break("<color=#fff>name</color>"), "color tag is not a break");
    check(!has_explicit_break("<sprite name=br>"), "sprite name is not a break");
    check(!is_single_line(true, true, 30, 32, true, false),
          "package breaks override a single-line source control");
    check(!is_single_line(false, true, 160, 32, true, true),
          "short text in a body rectangle keeps wrapping");
    check(!is_single_line(false, false, 0, 32, true, true),
          "unresolved rectangle is not classified as a one-line label");
    check(is_single_line(false, true, 40, 32, true, true),
          "compact button is classified as one line");
    check(multiline_spacing(2, 32, true) == 2,
          "explicit line-height does not receive duplicate compensation");
    check(multiline_spacing(2, 32, false) > 2,
          "ordinary multiline text has a spacing floor");
    int checks = 16;
    std::array<std::string, 6> battle_names{{"SkillNameText", "SkillNameField",
        "[Script]SkillAndCoinUI", "active", "[Script]UnitSkillInfoUI", "[Script]UnitActionUI"}};
    check(is_battle_skill_hierarchy(battle_names), "observed battle skill name is selected");
    ++checks;
    for (size_t depth = 0; depth < battle_names.size(); ++depth) {
        auto other = battle_names;
        other[depth] = depth == 0 ? "SkillDescText" : "SkillDetailPanel";
        check(!is_battle_skill_hierarchy(other), "description and non-battle hierarchy excluded");
        ++checks;
    }
    for (float ratio : {0.25f, 0.5f, 0.9f}) {
        for (float scale : {1.0f, 4.0f, 16.0f}) {
            for (bool bold : {false, true}) {
                const float old_weight = bold ? 0.75f / 4.0f : 0.0f;
                const float new_weight = bold ? kBattleSkillBoldWeight / 4.0f : 0.0f;
                const float old_face = (0.5f - (old_weight + kChineseFaceDilate -
                    kChineseOutlineWidth) * ratio / 2.0f) * scale - 0.5f;
                const float new_face = (0.5f - (new_weight + kBattleSkillFaceDilate -
                    kBattleSkillOutlineWidth) * ratio / 2.0f) * scale - 0.5f;
                check(new_face > old_face, "battle white face narrows with and without Bold");
                check(kBattleSkillOutlineWidth * ratio * scale >
                    kChineseOutlineWidth * ratio * scale, "battle black border grows at every scale");
                checks += 2;
            }
        }
    }
    // 用 TMP 官方 shader 的阈值公式检验生产材质参数：填充边界必须保持，黑边只能外扩。
    // 覆盖不同图集比例和渲染尺度，避免仅验证某个字号下两个常量碰巧相等。
    for (float ratio : {1.0f, 0.5f, 0.25f}) {
        for (float scale : {1.0f, 4.0f, 16.0f}) {
            const float font_weight = 0.05f;
            const float original_bias = (0.5f - font_weight * ratio * 0.5f) * scale - 0.5f;
            const float weight = (font_weight + kChineseFaceDilate) * ratio * 0.5f;
            const float bias = (0.5f - weight) * scale - 0.5f;
            const float outline = kChineseOutlineWidth * ratio * 0.5f * scale;
            check(std::fabs((bias + outline) - original_bias) < 0.00001f,
                  "Chinese outline preserves original face coverage at every SDF scale");
            check(bias - outline < original_bias,
                  "Chinese black outline expands outside the original glyph contour");
            checks += 2;
        }
    }
#if defined(__ANDROID__)
    g_terms = {{"保存", "保存"}, {"保存神域", "保存神域"},
               {"神域", "wrong-suffix"}, {"強度", "强度"}, {"以上", "或以上"}};
    rebuild_term_trie();
    std::string translated;
    check(translate_embedded_terms("保存神域 強度", &translated) &&
          translated == "保存神域 强度", "canonical identity span blocks shorter suffix rewrites");
    check(!translate_embedded_terms("保存", &translated) && translated == "保存",
          "identity-only match is not a replacement");
    check(translate_embedded_terms("<link=保存>保存</link> 強度", &translated) &&
          translated == "<link=保存>保存</link> 强度", "rich text and identity spans are retained");
    check(!translate_embedded_terms("3以上", &translated),
          "self-containing expansion is excluded from embedded lookup");
    check(!translate_embedded_terms("3或以上", &translated), "repeated translation is idempotent");
    checks += 5;
    // 带标点的 ASCII 术语也必须遵守边界，富文本属性始终是控制数据。
    g_terms = {{"[Binding]2", "束缚二层"}, {"or", "或"}, {"テスト", "测试"}};
    rebuild_term_trie();
    check(!translate_embedded_terms("[Binding]20", &translated), "punctuated ASCII term cannot consume a numeric prefix");
    check(translate_embedded_terms("[Binding]2", &translated) && translated == "束缚二层", "punctuated ASCII term still matches its complete token");
    check(!translate_embedded_terms("color orElse", &translated), "ASCII terms cannot split words");
    check(translate_embedded_terms("<color=#fff>テスト</color>", &translated) && translated == "<color=#fff>测试</color>", "rich text control data remains byte-identical");
    check(translate_embedded_terms("<link=テスト>テスト</link>", &translated) && translated == "<link=テスト>测试</link>", "Japanese control attributes do not count as untranslated visible text");
    check(!translate_embedded_terms("<えっと…>テスト", &translated), "Dante angle-bracket dialogue still counts as visible Japanese");
    checks += 6;

    prepare_probe_runtime();
    reset_probe_origins();
    g_index = {{"承諾", "同意"}, {"同意", "동의"}, {"混乱解除", "解除混乱"},
               {"元文模板", "最大{0}点、目标{1}"}, {"段落一\n段落二", "第一段\n第二段"}};
    g_terms = {{"混乱", "攻击陷入混乱"}, {"最大", "错误"}, {"日本語", "中文"}};
    rebuild_term_trie();
    void *first = translate_managed_string(probe_utf8("承諾"));
    check(probe_text(first) == "同意" && translate_managed_string(first) == first,
          "owned exact output cannot follow another source key");
    check(probe_text(translate_managed_string(probe_utf8("同意"))) == "동의",
          "a fresh Japanese object with the same content still follows the original index");
    void *term_output = translate_managed_string(probe_utf8("混乱解除"));
    check(probe_text(translate_managed_string(term_output)) == "解除混乱", "owned output bypasses cross-term corruption");
    {
        FormatterTranslationScope scope;
        translate_managed_string(probe_utf8("元文模板"));
        void *formatted = translate_managed_string(probe_utf8("最大3点、目标日本語"), &scope.templates);
        check(probe_text(formatted) == "最大3点、目标中文", "formatter literals stay Chinese while Japanese parameters are translated");
        check(translate_managed_string(formatted) == formatted, "formatted copy retains managed provenance for TMP");
        void *same_parameter = translate_managed_string(probe_utf8("最大3点、目标最大"), &scope.templates);
        check(probe_text(same_parameter) == "最大3点、目标错误", "parameter matching a template literal is not overprotected");
    }
    check(probe_text(translate_managed_string(probe_utf8("最大3"))) == "错误3", "formatter template protection never leaks to unrelated calls");
    auto origin = g_translated_string_origins.find(first);
    if (origin != g_translated_string_origins.end()) probe_handles[origin->second] = nullptr;
    check(!is_translation_owned_string(first), "expired weak handle cannot authorize a reused address");
    reset_probe_origins();
    first = translate_managed_string(probe_utf8("承諾"));
    size_t refused = 0;
    for (size_t index = 0; index < 32; ++index) {
        if (!remember_translation_owned_string(probe_utf8("固定字面"), 16)) ++refused;
    }
    check(probe_handles.size() <= 16 && g_translated_string_origins.size() <= 16 && refused > 0,
          "weak handle cache rejects new ownership at a fixed upper bound");
    check(translate_managed_string(first) == first, "live field provenance cannot be evicted when capacity is exhausted");
    reset_probe_origins();
    check(probe_handles.empty(), "weak handles are released on audit reset");
    std::string normalized;
    check(!strip_line_height_tags("段落一<line-height=120%>\n段落二", &normalized), "unverified line height is not stripped");
    check(!strip_line_height_tags("<line-height=170%>段落一<line-height=120%>\n段落二", &normalized), "mixed line heights refuse Dante normalization");
    check(strip_line_height_tags("段落一<line-height=170%>\n<line-height=100%>段落二", &normalized) && normalized == "段落一\n段落二", "only verified Dante tags are normalized");
    check(probe_text(translate_managed_string(probe_utf8("段落一<line-height=170%>\n<line-height=100%>段落二"))) ==
          "第一段<line-height=170%>\n<line-height=100%>第二段", "Dante exact fallback retains verified paragraph layout");
    reset_probe_origins();
    checks += 15;
    g_index["外层模板"] = "给予{0}";
    g_index["弱点"] = "脆弱";
    g_index["脆弱"] = "易损";
    g_terms["脆弱"] = "易损";
    rebuild_term_trie();
    {
        FormatterTranslationScope outer;
        translate_managed_string(probe_utf8("外层模板"));
        {
            FormatterTranslationScope inner;
            void *nested = translate_managed_string(probe_utf8("弱点"));
            remember_formatter_result(&inner, nested);
        }
        void *result = translate_managed_string(probe_utf8("给予脆弱"), &outer.templates);
        check(probe_text(result) == "给予脆弱", "nested formatter final output preserves provenance in parent parameters");
    }
    {
        FormatterTranslationScope truncated;
        remember_formatter_translation(std::string(65537, 'a'));
        void *result = translate_managed_string(probe_utf8("最大3"), &truncated.templates, truncated.truncated);
        check(probe_text(result) == "最大3" && is_translation_owned_string(result), "truncated template provenance keeps existing formatter output unchanged");
    }
    reset_probe_origins();
    checks += 2;
    {
        using limbus_translation_guard::protected_template_spans;
        auto spans = protected_template_spans("最大  3点1000、{中}", {"最大{0,3}点{1:N0}、{{中}}"});
        check(spans.size() == 3 && spans.front().begin == 0 && spans.front().end == std::string("最大").size(),
              "alignment formats and escaped braces protect only translated literals");
        spans = protected_template_spans("最大最大", {"最大{0}最大"});
        check(spans.size() == 1 && spans[0].begin == 0 && spans[0].end == std::string("最大最大").size(),
              "repeated adjacent literal matches merge into one UTF-8 span");
        check(protected_template_spans("123、%", {"{0}、{1}%"}).empty(), "numeric punctuation templates cannot protect arbitrary parameters");
        checks += 3;
    }
    for (const char *field : {"speaker", "displayName", "statText", "goalDescription1",
                             "goalDescription2", "goalDescription3", "goalDescription4",
                             "goalDescription5", "goalDescription6", "goalDescription7",
                             "goalDescription8"}) {
        check(is_display_string_field(field), "reviewed RPG display field is accepted by native");
        ++checks;
    }
    check(!is_display_string_field("key") && !is_display_string_field("index"),
          "RPG pairing identifiers remain metadata");
    ++checks;
#endif
    if (failures == 0) std::printf("PASS: %d native translation regressions\n", checks);
#if defined(__ANDROID__)
    if (failures == 0 && argc == 4 && std::string(argv[1]) == "--audit") {
        return audit_lookup(argv[2], argv[3]);
    }
#endif
    return failures == 0 ? 0 : 1;
}
