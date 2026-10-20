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

// Standalone audit of the exact production lookup pipeline. No Unity or game
// process is loaded, and only source hashes/results are written to the report.
static int audit_lookup(const char *input_path, const char *index_path) {
    if (!load_index_file(index_path)) return 2;
    FILE *input = fopen(input_path, "rb");
    if (input == nullptr) return 2;
    FILE *output = fopen("/data/local/tmp/limbus-translation-audit.tsv", "wb");
    if (output == nullptr) { fclose(input); return 2; }
    fprintf(output, "sourceHash64\tresult\n");
    g_string_length = probe_length;
    g_string_chars = probe_chars;
    g_string_new_utf16 = probe_new;
    uint32_t count = 0;
    bool valid = read_u32(input, &count) && count <= kMaxEntries;
    size_t expected = 0, unchanged = 0, different = 0, unpaired = 0;
    for (uint32_t i = 0; valid && i < count; ++i) {
        std::string hash, source, translation;
        valid = read_string(input, &hash) && read_string(input, &source) && read_string(input, &translation);
        if (!valid) break;
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
        fprintf(output, "%s\t%s\n", hash.c_str(), status);
        probe_strings.clear();
    }
    fclose(input);
    fclose(output);
    printf("Native audit rows=%u matches=%zu unchanged=%zu different=%zu translated_without_pair=%zu valid=%d\n",
           count, expected, unchanged, different, unpaired, valid);
    return valid ? 0 : 2;
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
    check(translate_embedded_terms("3以上", &translated) && translated == "3或以上",
          "expanding terms still translate once");
    check(!translate_embedded_terms("3或以上", &translated), "repeated translation is idempotent");
    checks += 5;
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
