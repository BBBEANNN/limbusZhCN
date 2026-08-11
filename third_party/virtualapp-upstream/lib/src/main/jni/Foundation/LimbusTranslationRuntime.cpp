#include "LimbusTranslationRuntime.h"
#include "../Substrate/SubstrateHook.h"

#include <android/log.h>
#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <time.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <unistd.h>

#define LT_TAG "LimbusTranslation"
#define LT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LT_TAG, __VA_ARGS__)
#define LT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LT_TAG, __VA_ARGS__)

namespace {

const unsigned char kIndexMagic[] = {'L', 'Z', 'T', 'I', '1', 0};
const uint32_t kIndexSchema = 7;
const uint32_t kMaxEntries = 1000000;
const uint32_t kMaxStringBytes = 16 * 1024 * 1024;

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_font_state_lock = PTHREAD_MUTEX_INITIALIZER;
char g_active_pointer[PATH_MAX] = {};
std::unordered_map<std::string, std::string> g_index;
std::unordered_map<std::string, std::string> g_terms;
struct TermTrieNode {
    std::unordered_map<unsigned char, size_t> children;
    const std::string *translation = nullptr;
};
std::vector<TermTrieNode> g_term_trie;
bool g_configured = false;
bool g_resolver_reported = false;
bool g_probe_started = false;
bool g_hook_installed = false;
bool g_font_attempted = false;
void *g_tmp_font_asset = nullptr;
// 译文来源按 UTF-8 内容登记，避免游戏复制 IL2CPP String 后丢失仅依赖对象地址的字体标记。
std::unordered_set<std::string> g_translated_managed_texts;
struct TmpComponentState {
    void *font = nullptr;
    void *shared_material = nullptr;
    float line_spacing = 0.0f;
    bool has_line_spacing = false;
};
std::unordered_map<void *, TmpComponentState> g_original_tmp_states;
uint64_t g_translation_hits = 0;
char g_il2cpp_path[PATH_MAX] = {};

struct Il2CppStringLayout {
    void *klass;
    void *monitor;
    int32_t length;
    uint16_t chars[1];
};

typedef int32_t (*string_length_fn)(void *);
typedef const uint16_t *(*string_chars_fn)(void *);
typedef void *(*string_new_utf16_fn)(const uint16_t *, int32_t);
typedef void *(*create_dynamic_font_fn)(void *, int32_t, const void *);
typedef void *(*create_tmp_font_fn)(void *, const void *);
typedef void *(*runtime_invoke_fn)(const void *, void *, void **, void **);
typedef void *(*object_get_class_fn)(void *);
typedef void *(*object_unbox_fn)(void *);
typedef const void *(*class_get_method_global_fn)(void *, const char *, int);
typedef void *(*il2cpp_init_fn)(const char *);
typedef void *(*dlsym_fn)(void *, const char *);
typedef void (*tmp_set_font_fn)(void *, void *, const void *);
typedef void *(*tmp_get_font_fn)(void *, const void *);
typedef void (*tmp_set_text_fn)(void *, void *, const void *);
typedef void (*tmp_set_text_string_fn)(void *, void *, bool, const void *);
typedef int32_t (*get_language_fn)(void *, const void *);
typedef void *(*class_get_parent_fn)(void *);
typedef const void *(*class_get_fields_fn)(void *, void **);
typedef const char *(*field_get_name_fn)(const void *);
typedef const void *(*field_get_type_fn)(const void *);
typedef void (*field_get_value_fn)(void *, const void *, void *);
typedef void (*field_set_value_fn)(void *, const void *, void *);

string_length_fn g_string_length = nullptr;
string_chars_fn g_string_chars = nullptr;
string_new_utf16_fn g_string_new_utf16 = nullptr;
create_dynamic_font_fn g_create_dynamic_font = nullptr;
create_tmp_font_fn g_create_tmp_font = nullptr;
runtime_invoke_fn g_runtime_invoke = nullptr;
object_get_class_fn g_object_get_class = nullptr;
object_unbox_fn g_object_unbox = nullptr;
class_get_method_global_fn g_class_get_method = nullptr;
tmp_set_font_fn g_tmp_set_font = nullptr;
tmp_get_font_fn g_tmp_get_font = nullptr;
tmp_set_text_fn g_original_tmp_set_text = nullptr;
tmp_set_text_fn g_original_tmp_set_text_one = nullptr;
tmp_set_text_fn g_original_ui_set_text = nullptr;
tmp_set_text_string_fn g_original_tmp_set_text_string = nullptr;
const void *g_create_dynamic_font_info = nullptr;
const void *g_create_tmp_font_info = nullptr;
const void *g_tmp_set_font_info = nullptr;
const void *g_tmp_get_font_info = nullptr;
const void *g_get_fallback_fonts_info = nullptr;
const void *g_tmp_get_font_material_info = nullptr;
const void *g_tmp_get_shared_material_info = nullptr;
const void *g_tmp_set_shared_material_info = nullptr;
const void *g_tmp_get_line_spacing_info = nullptr;
const void *g_tmp_set_line_spacing_info = nullptr;
const void *g_tmp_get_font_size_info = nullptr;
const void *g_material_get_float_info = nullptr;
const void *g_material_set_float_info = nullptr;
const void *g_material_get_color_info = nullptr;
const void *g_material_set_color_info = nullptr;
il2cpp_init_fn g_original_il2cpp_init = nullptr;
dlsym_fn g_original_unity_dlsym = nullptr;
get_language_fn g_original_get_language = nullptr;
get_language_fn g_original_global_get_language = nullptr;
class_get_parent_fn g_class_get_parent = nullptr;
class_get_fields_fn g_class_get_fields = nullptr;
field_get_name_fn g_field_get_name = nullptr;
field_get_type_fn g_field_get_type = nullptr;
field_get_value_fn g_field_get_value = nullptr;
field_set_value_fn g_field_set_value = nullptr;
typedef char *(*type_get_name_owned_fn)(const void *);
typedef void (*il2cpp_free_owned_fn)(void *);
type_get_name_owned_fn g_type_get_name = nullptr;
il2cpp_free_owned_fn g_il2cpp_free = nullptr;
std::atomic<uint32_t> g_language_hook_hits{0};
std::atomic<uint64_t> g_acquisition_hits{0};
std::atomic<uint64_t> g_nested_list_hits{0};
std::atomic<uint64_t> g_tmp_primary_font_switches{0};

bool append_utf8(uint32_t codepoint, std::string *output) {
    if (codepoint <= 0x7f) {
        output->push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        output->push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        output->push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0x10ffff) {
        output->push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output->push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        return false;
    }
    return true;
}

bool utf16_to_utf8(const uint16_t *input, int32_t length, std::string *output) {
    if (input == nullptr || length < 0) return false;
    output->clear();
    output->reserve(static_cast<size_t>(length));
    for (int32_t i = 0; i < length; ++i) {
        uint32_t codepoint = input[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
            if (++i >= length || input[i] < 0xdc00 || input[i] > 0xdfff) return false;
            codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (input[i] - 0xdc00);
        } else if (codepoint >= 0xdc00 && codepoint <= 0xdfff) {
            return false;
        }
        if (!append_utf8(codepoint, output)) return false;
    }
    return true;
}

bool utf8_to_utf16(const std::string &input, std::u16string *output) {
    output->clear();
    for (size_t i = 0; i < input.size();) {
        unsigned char first = static_cast<unsigned char>(input[i++]);
        uint32_t codepoint = 0;
        int continuation = 0;
        if (first <= 0x7f) codepoint = first;
        else if ((first & 0xe0) == 0xc0) { codepoint = first & 0x1f; continuation = 1; }
        else if ((first & 0xf0) == 0xe0) { codepoint = first & 0x0f; continuation = 2; }
        else if ((first & 0xf8) == 0xf0) { codepoint = first & 0x07; continuation = 3; }
        else return false;
        if (i + continuation > input.size()) return false;
        for (int j = 0; j < continuation; ++j) {
            unsigned char next = static_cast<unsigned char>(input[i++]);
            if ((next & 0xc0) != 0x80) return false;
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if ((continuation == 1 && codepoint < 0x80) ||
            (continuation == 2 && codepoint < 0x800) ||
            (continuation == 3 && codepoint < 0x10000) ||
            codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff)) return false;
        if (codepoint <= 0xffff) {
            output->push_back(static_cast<char16_t>(codepoint));
        } else {
            codepoint -= 0x10000;
            output->push_back(static_cast<char16_t>(0xd800 + (codepoint >> 10)));
            output->push_back(static_cast<char16_t>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return true;
}

bool is_usable_display_text(const std::string &value) {
    std::u16string utf16;
    if (value.empty() || !utf8_to_utf16(value, &utf16)) return false;
    for (char16_t character : utf16) {
        // U+FFFD 与非法控制字符都说明资源在进入索引前已经损坏。
        // 保持日文原文比继续渲染方框、不可见控制符或乱码更安全。
        if (character == 0xfffd ||
            (character < 0x20 && character != u'\n' && character != u'\r' &&
             character != u'\t') ||
            (character >= 0x7f && character <= 0x9f)) {
            return false;
        }
    }
    return true;
}

void *new_managed_string(const std::string &value) {
    std::u16string utf16;
    if (g_string_new_utf16 == nullptr || !utf8_to_utf16(value, &utf16)) return nullptr;
    return g_string_new_utf16(reinterpret_cast<const uint16_t *>(utf16.data()),
                              static_cast<int32_t>(utf16.size()));
}

void rebuild_term_trie() {
    g_term_trie.clear();
    g_term_trie.emplace_back();
    for (const auto &entry : g_terms) {
        // A term replacement must be idempotent across acquisition and TMP
        // layers.  "以上" -> "或以上", for example, still contains its source
        // and would grow another "或" every time the string crosses a hook.
        if (!is_usable_display_text(entry.first) ||
            !is_usable_display_text(entry.second) ||
            entry.second.find(entry.first) != std::string::npos) {
            continue;
        }
        size_t node = 0;
        for (unsigned char byte : entry.first) {
            auto child = g_term_trie[node].children.find(byte);
            if (child == g_term_trie[node].children.end()) {
                size_t next = g_term_trie.size();
                g_term_trie[node].children[byte] = next;
                g_term_trie.emplace_back();
                node = next;
            } else {
                node = child->second;
            }
        }
        g_term_trie[node].translation = &entry.second;
    }
}

bool translate_embedded_terms(const std::string &source, std::string *output) {
    if (g_term_trie.size() <= 1) return false;
    output->clear();
    output->reserve(source.size());
    bool changed = false;
    for (size_t offset = 0; offset < source.size();) {
        // TMP rich-text markup is control data, not display text.  In
        // particular, an ASCII term such as "or" must never rewrite the tail
        // of <color> into <col或>, which makes TMP display the broken tag.
        if (source[offset] == '<') {
            size_t tag_end = source.find('>', offset + 1);
            if (tag_end != std::string::npos) {
                output->append(source, offset, tag_end - offset + 1);
                offset = tag_end + 1;
                continue;
            }
        }
        size_t node = 0;
        size_t best_end = offset;
        const std::string *best = nullptr;
        for (size_t cursor = offset; cursor < source.size(); ++cursor) {
            unsigned char byte = static_cast<unsigned char>(source[cursor]);
            auto child = g_term_trie[node].children.find(byte);
            if (child == g_term_trie[node].children.end()) break;
            node = child->second;
            if (g_term_trie[node].translation != nullptr) {
                best = g_term_trie[node].translation;
                best_end = cursor + 1;
            }
        }
        if (best != nullptr) {
            bool ascii_word = true;
            for (size_t index = offset; index < best_end; ++index) {
                unsigned char byte = static_cast<unsigned char>(source[index]);
                if (!(byte >= '0' && byte <= '9') &&
                    !(byte >= 'A' && byte <= 'Z') &&
                    !(byte >= 'a' && byte <= 'z') && byte != '_') {
                    ascii_word = false;
                    break;
                }
            }
            auto is_ascii_word_byte = [](unsigned char byte) {
                return (byte >= '0' && byte <= '9') ||
                       (byte >= 'A' && byte <= 'Z') ||
                       (byte >= 'a' && byte <= 'z') || byte == '_';
            };
            if (ascii_word &&
                ((offset > 0 && is_ascii_word_byte(
                        static_cast<unsigned char>(source[offset - 1]))) ||
                 (best_end < source.size() && is_ascii_word_byte(
                        static_cast<unsigned char>(source[best_end]))))) {
                best = nullptr;
            }
        }
        if (best != nullptr) {
            output->append(*best);
            offset = best_end;
            changed = true;
        } else {
            output->push_back(source[offset++]);
        }
    }
    return changed;
}

bool strip_line_height_tags(const std::string &source, std::string *output) {
    output->clear();
    output->reserve(source.size());
    bool changed = false;
    for (size_t offset = 0; offset < source.size();) {
        if (source.compare(offset, 13, "<line-height=") == 0) {
            size_t end = source.find('>', offset + 13);
            if (end != std::string::npos) {
                offset = end + 1;
                changed = true;
                continue;
            }
        }
        output->push_back(source[offset++]);
    }
    return changed;
}

std::string apply_dante_line_height(const std::string &translation) {
    std::string output;
    output.reserve(translation.size() + 64);
    for (size_t offset = 0; offset < translation.size(); ++offset) {
        if (translation[offset] != '\n') {
            output.push_back(translation[offset]);
            continue;
        }
        output += "<line-height=170%>\n";
        if (offset + 1 < translation.size() && translation[offset + 1] == '-') {
            output.push_back('-');
            output += "<line-height=100%>";
            ++offset;
        } else {
            output += "<line-height=100%>";
        }
    }
    return output;
}

void *translate_managed_string(void *managed_text) {
    if (managed_text == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return managed_text;
    }
    int32_t length = g_string_length(managed_text);
    std::string source;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &source)) {
        return managed_text;
    }
    std::unordered_map<std::string, std::string>::const_iterator match = g_index.find(source);
    std::string translated;
    if (match != g_index.end()) {
        translated = match->second;
    } else {
        std::string normalized;
        bool line_height_normalized = strip_line_height_tags(source, &normalized);
        if (line_height_normalized) {
            match = g_index.find(normalized);
        }
        if (line_height_normalized && match != g_index.end()) {
            translated = apply_dante_line_height(match->second);
        } else if (!translate_embedded_terms(source, &translated)) {
            return managed_text;
        }
    }
    if (!is_usable_display_text(translated)) return managed_text;
    void *replacement = new_managed_string(translated);
    if (replacement != nullptr) {
        pthread_mutex_lock(&g_font_state_lock);
        // 只登记接管层确实生成过的完整译文，不能按“含非 ASCII”泛化为中文主字体。
        g_translated_managed_texts.insert(translated);
        pthread_mutex_unlock(&g_font_state_lock);
        uint64_t hits = g_acquisition_hits.fetch_add(1, std::memory_order_relaxed) + 1;
        if (hits == 1 || hits % 500 == 0) {
            LT_LOGI("Text acquisition translation hits=%llu",
                    static_cast<unsigned long long>(hits));
        }
    }
    return replacement == nullptr ? managed_text : replacement;
}

bool is_translated_managed_string(void *managed_text) {
    if (managed_text == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return false;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return false;
    }
    pthread_mutex_lock(&g_font_state_lock);
    bool translated = g_translated_managed_texts.find(text) !=
            g_translated_managed_texts.end();
    pthread_mutex_unlock(&g_font_state_lock);
    return translated;
}

bool is_display_string_field(const char *name) {
    if (name == nullptr) return false;
    static const char *const fields[] = {
            "content", "dialog", "dlg", "teller", "name", "nameWithTitle",
            "desc", "description", "title", "summary", "flavor", "place",
            "shortName", "abName", "simpleDesc", "successDesc", "failureDesc",
            "message", "messageDesc", "result", "text", "subText", "mainText",
            "rawDesc", "acquisitionMethod", "skinItemTitle", "skinItemDesc",
            "oneLineTitle", "nickName", "longName", "specialName",
            "abnormalityName", "panicName", "behaveDesc", "eventDesc", "prevDesc",
            "subDesc", "panicDescription", "lowMoraleDescription", "codeName", "clue",
            "sentence", "story", "openCondition", "openConditionNumber",
            "relatedChapterText", "askLevelUp", "company", "area", "chapter",
            "chapterNumber", "chaptertitle", "parttitle", "timeline", "teacher",
            "add", "min", "variation", "variation2"
    };
    for (const char *field : fields) {
        if (strcmp(name, field) == 0) return true;
    }
    return false;
}

void translate_object_string_fields(void *instance) {
    if (instance == nullptr || g_object_get_class == nullptr || g_class_get_parent == nullptr ||
        g_class_get_fields == nullptr || g_field_get_name == nullptr ||
        g_field_get_type == nullptr || g_field_get_value == nullptr ||
        g_field_set_value == nullptr || g_type_get_name == nullptr || g_il2cpp_free == nullptr) {
        return;
    }
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            if (!is_display_string_field(field_name)) continue;
            char *type_name = g_type_get_name(g_field_get_type(field));
            bool is_string = type_name != nullptr && strcmp(type_name, "System.String") == 0;
            if (type_name != nullptr) g_il2cpp_free(type_name);
            if (!is_string) continue;
            void *value = nullptr;
            g_field_get_value(instance, field, &value);
            void *replacement = translate_managed_string(value);
            if (replacement != value) g_field_set_value(instance, field, replacement);
        }
    }
}

bool is_nested_text_field(const char *name) {
    if (name == nullptr) return false;
    static const char *const fields[] = {
            "levelList", "levellist", "coinList", "coinlist",
            "coinDescs", "coindescs"
    };
    for (const char *field : fields) {
        if (strcmp(name, field) == 0) return true;
    }
    return false;
}

void translate_object_graph_impl(void *instance, int depth,
                                 std::unordered_set<void *> *visited) {
    if (instance == nullptr || depth > 4 || visited == nullptr ||
        !visited->insert(instance).second) {
        return;
    }
    translate_object_string_fields(instance);
    if (g_object_get_class == nullptr || g_class_get_parent == nullptr ||
        g_class_get_fields == nullptr || g_field_get_name == nullptr ||
        g_field_get_value == nullptr || g_class_get_method == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return;
    }
    for (void *klass = g_object_get_class(instance); klass != nullptr;
         klass = g_class_get_parent(klass)) {
        void *iterator = nullptr;
        const void *field = nullptr;
        while ((field = g_class_get_fields(klass, &iterator)) != nullptr) {
            const char *field_name = g_field_get_name(field);
            if (!is_nested_text_field(field_name)) continue;
            void *list = nullptr;
            g_field_get_value(instance, field, &list);
            void *list_class = list == nullptr ? nullptr : g_object_get_class(list);
            const void *count_method = list_class == nullptr ? nullptr :
                    g_class_get_method(list_class, "get_Count", 0);
            const void *item_method = list_class == nullptr ? nullptr :
                    g_class_get_method(list_class, "get_Item", 1);
            if (count_method == nullptr || item_method == nullptr) continue;
            void *exception = nullptr;
            void *boxed_count = g_runtime_invoke(count_method, list, nullptr, &exception);
            int32_t *count_value = exception == nullptr && boxed_count != nullptr ?
                    static_cast<int32_t *>(g_object_unbox(boxed_count)) : nullptr;
            int32_t count = count_value == nullptr ? 0 : *count_value;
            if (count < 0 || count > 512) continue;
            uint64_t nested_hits = g_nested_list_hits.fetch_add(1, std::memory_order_relaxed) + 1;
            if (nested_hits == 1 || nested_hits % 1000 == 0) {
                LT_LOGI("Nested text list traversal hits=%llu field=%s count=%d depth=%d",
                        static_cast<unsigned long long>(nested_hits), field_name, count, depth);
            }
            for (int32_t index = 0; index < count; ++index) {
                void *args[] = {&index};
                exception = nullptr;
                void *child = g_runtime_invoke(item_method, list, args, &exception);
                if (exception == nullptr && child != nullptr) {
                    translate_object_graph_impl(child, depth + 1, visited);
                }
            }
        }
    }
}

void translate_object_graph(void *instance) {
    std::unordered_set<void *> visited;
    translate_object_graph_impl(instance, 0, &visited);
}

typedef void *(*acquisition0_fn)(void *, const void *);
typedef void *(*acquisition1_fn)(void *, void *, const void *);
typedef void *(*acquisition2_fn)(void *, void *, void *, const void *);
const size_t kAcquisitionSlots = 64;
acquisition0_fn g_acquisition0[kAcquisitionSlots] = {};
acquisition1_fn g_acquisition1[kAcquisitionSlots] = {};
acquisition2_fn g_acquisition2[kAcquisitionSlots] = {};

void *invoke_acquisition0(size_t slot, void *instance, const void *method) {
    translate_object_graph(instance);
    return translate_managed_string(g_acquisition0[slot](instance, method));
}
void *invoke_acquisition1(size_t slot, void *instance, void *arg0, const void *method) {
    translate_object_graph(instance);
    return translate_managed_string(g_acquisition1[slot](instance, arg0, method));
}
void *invoke_acquisition2(size_t slot, void *instance, void *arg0, void *arg1,
                          const void *method) {
    translate_object_graph(instance);
    return translate_managed_string(g_acquisition2[slot](instance, arg0, arg1, method));
}

#define ACQUISITION_SLOTS(X) \
    X(0) X(1) X(2) X(3) X(4) X(5) X(6) X(7) \
    X(8) X(9) X(10) X(11) X(12) X(13) X(14) X(15) \
    X(16) X(17) X(18) X(19) X(20) X(21) X(22) X(23) \
    X(24) X(25) X(26) X(27) X(28) X(29) X(30) X(31) \
    X(32) X(33) X(34) X(35) X(36) X(37) X(38) X(39) \
    X(40) X(41) X(42) X(43) X(44) X(45) X(46) X(47) \
    X(48) X(49) X(50) X(51) X(52) X(53) X(54) X(55) \
    X(56) X(57) X(58) X(59) X(60) X(61) X(62) X(63)
#define DEFINE_ACQUISITION_WRAPPERS(N) \
    void *replacement_acquisition0_##N(void *i, const void *m) { \
        return invoke_acquisition0(N, i, m); \
    } \
    void *replacement_acquisition1_##N(void *i, void *a, const void *m) { \
        return invoke_acquisition1(N, i, a, m); \
    } \
    void *replacement_acquisition2_##N(void *i, void *a, void *b, const void *m) { \
        return invoke_acquisition2(N, i, a, b, m); \
    }
ACQUISITION_SLOTS(DEFINE_ACQUISITION_WRAPPERS)
#undef DEFINE_ACQUISITION_WRAPPERS
#define ACQUISITION0_ENTRY(N) replacement_acquisition0_##N,
#define ACQUISITION1_ENTRY(N) replacement_acquisition1_##N,
#define ACQUISITION2_ENTRY(N) replacement_acquisition2_##N,
acquisition0_fn const kAcquisition0Replacements[] = {ACQUISITION_SLOTS(ACQUISITION0_ENTRY)};
acquisition1_fn const kAcquisition1Replacements[] = {ACQUISITION_SLOTS(ACQUISITION1_ENTRY)};
acquisition2_fn const kAcquisition2Replacements[] = {ACQUISITION_SLOTS(ACQUISITION2_ENTRY)};
#undef ACQUISITION0_ENTRY
#undef ACQUISITION1_ENTRY
#undef ACQUISITION2_ENTRY
#undef ACQUISITION_SLOTS

bool ensure_tmp_font() {
    pthread_mutex_lock(&g_lock);
    if (!g_font_attempted) {
        g_font_attempted = true;
        std::string font_file(g_active_pointer);
        size_t separator = font_file.find_last_of('/');
        if (separator != std::string::npos) font_file.resize(separator);
        separator = font_file.find_last_of('/');
        if (separator != std::string::npos) font_file.resize(separator);
        font_file += "/runtime-font/ChineseFont-6541a94a.ttf";
        void *font_path = new_managed_string(font_file);
        int32_t face_index = 0;
        int32_t sampling_size = 32;
        int32_t atlas_padding = 9;
        int32_t render_mode = 4169;  // GlyphRenderMode.SDFAA.
        // The bundled Sarasa CJK face has substantially larger glyph contours
        // than the system fallback.  A 1024 atlas fills during the first skill
        // screen and TMP then drops glyphs that were already visible.
        int32_t atlas_width = 4096;
        int32_t atlas_height = 4096;
        int32_t population_mode = 1;  // AtlasPopulationMode.Dynamic.
        uint8_t multi_atlas = 1;
        void *asset_args[] = {
                font_path, &face_index, &sampling_size, &atlas_padding, &render_mode,
                &atlas_width, &atlas_height, &population_mode, &multi_atlas
        };
        void *exception = nullptr;
        if (font_path != nullptr && g_runtime_invoke != nullptr) {
            g_tmp_font_asset = g_runtime_invoke(
                    g_create_tmp_font_info, nullptr, asset_args, &exception);
        }
        void *fallback_list = nullptr;
        void *add_exception = nullptr;
        if (g_tmp_font_asset != nullptr && exception == nullptr &&
            g_get_fallback_fonts_info != nullptr && g_object_get_class != nullptr) {
            fallback_list = g_runtime_invoke(
                    g_get_fallback_fonts_info, nullptr, nullptr, &add_exception);
            void *list_class = fallback_list == nullptr ? nullptr :
                    g_object_get_class(fallback_list);
            const void *add_method = list_class == nullptr || g_class_get_method == nullptr ? nullptr :
                    g_class_get_method(list_class, "Add", 1);
            void *add_args[] = {g_tmp_font_asset};
            if (add_method != nullptr) {
                g_runtime_invoke(add_method, fallback_list, add_args, &add_exception);
            }
            LT_LOGI("Registered TMP fallback font list=%p add=%p exception=%p",
                    fallback_list, add_method, add_exception);
        }
        LT_LOGI("Dynamic CJK TMP font creation path=%s face=%d tmpFont=%p exception=%p",
                font_file.c_str(), face_index,
                g_tmp_font_asset, exception);
    }
    bool ready = g_tmp_font_asset != nullptr;
    pthread_mutex_unlock(&g_lock);
    return ready;
}

bool invoke_float_getter(const void *method_info, void *instance, float *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    memcpy(value, unboxed, sizeof(*value));
    return true;
}

void invoke_float_setter(const void *method_info, void *instance, float value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {&value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

void *invoke_object_getter(const void *method_info, void *instance) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return nullptr;
    void *exception = nullptr;
    void *result = g_runtime_invoke(method_info, instance, nullptr, &exception);
    return exception == nullptr ? result : nullptr;
}

void invoke_object_setter(const void *method_info, void *instance, void *value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    void *args[] = {value};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

struct MaterialOutlineStyle {
    float width = 0.0f;
    float color[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    bool has_width = false;
    bool has_color = false;
};

MaterialOutlineStyle read_material_outline(void *material) {
    MaterialOutlineStyle style;
    if (material == nullptr || g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return style;
    }
    void *width_name = new_managed_string("_OutlineWidth");
    void *color_name = new_managed_string("_OutlineColor");
    if (width_name != nullptr && g_material_get_float_info != nullptr) {
        void *args[] = {width_name};
        void *exception = nullptr;
        void *boxed = g_runtime_invoke(g_material_get_float_info, material, args, &exception);
        void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
        if (unboxed != nullptr) {
            memcpy(&style.width, unboxed, sizeof(style.width));
            style.has_width = true;
        }
    }
    if (color_name != nullptr && g_material_get_color_info != nullptr) {
        void *args[] = {color_name};
        void *exception = nullptr;
        void *boxed = g_runtime_invoke(g_material_get_color_info, material, args, &exception);
        void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
        if (unboxed != nullptr) {
            memcpy(style.color, unboxed, sizeof(style.color));
            style.has_color = true;
        }
    }
    return style;
}

void apply_material_outline(void *material, const MaterialOutlineStyle &style) {
    if (material == nullptr || !style.has_width || style.width <= 0.0001f ||
        g_runtime_invoke == nullptr) {
        return;
    }
    void *width_name = new_managed_string("_OutlineWidth");
    void *color_name = new_managed_string("_OutlineColor");
    if (width_name != nullptr && g_material_set_float_info != nullptr) {
        float width = style.width;
        void *args[] = {width_name, &width};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_float_info, material, args, &exception);
    }
    if (color_name != nullptr && g_material_set_color_info != nullptr) {
        // 拼点/技能组件原材质已有描边宽度时，中文材质继承宽度并统一为不透明黑边。
        // 这样既保留游戏层级感，也不会给原本没有描边的普通正文强行加边。
        float black[4] = {0.0f, 0.0f, 0.0f,
                          style.has_color ? std::max(style.color[3], 0.95f) : 1.0f};
        void *args[] = {color_name, black};
        void *exception = nullptr;
        g_runtime_invoke(g_material_set_color_info, material, args, &exception);
    }
}

TmpComponentState capture_tmp_component_state(void *instance, void *font) {
    TmpComponentState candidate;
    candidate.font = font;
    candidate.shared_material = invoke_object_getter(g_tmp_get_shared_material_info, instance);
    candidate.has_line_spacing = invoke_float_getter(
            g_tmp_get_line_spacing_info, instance, &candidate.line_spacing);
    pthread_mutex_lock(&g_font_state_lock);
    auto inserted = g_original_tmp_states.emplace(instance, candidate);
    TmpComponentState state = inserted.first->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return state;
}

bool find_tmp_component_state(void *instance, TmpComponentState *state) {
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    bool found = saved != g_original_tmp_states.end();
    if (found && state != nullptr) *state = saved->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return found;
}

void restore_tmp_component_state(void *instance) {
    TmpComponentState state;
    bool restore = false;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_tmp_states.find(instance);
    if (saved != g_original_tmp_states.end()) {
        state = saved->second;
        g_original_tmp_states.erase(saved);
        restore = true;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    if (!restore) return;
    // 组件会被列表复用；必须按字体、材质、行距的顺序恢复完整原始样式。
    if (g_tmp_set_font != nullptr) g_tmp_set_font(instance, state.font, g_tmp_set_font_info);
    if (state.shared_material != nullptr) {
        invoke_object_setter(g_tmp_set_shared_material_info, instance, state.shared_material);
    }
    if (state.has_line_spacing) {
        invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
    }
}

void apply_translated_tmp_style(void *instance, void *managed_text,
                                const TmpComponentState &state) {
    MaterialOutlineStyle outline = read_material_outline(state.shared_material);
    void *translated_material = invoke_object_getter(g_tmp_get_font_material_info, instance);
    apply_material_outline(translated_material, outline);

    if (!state.has_line_spacing || managed_text == nullptr || g_string_length == nullptr ||
        g_string_chars == nullptr) {
        return;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return;
    }
    float target_spacing = state.line_spacing;
    bool has_explicit_line_height = text.find("<line-height=") != std::string::npos;
    if (!has_explicit_line_height) {
        float font_size = 0.0f;
        invoke_float_getter(g_tmp_get_font_size_info, instance, &font_size);
        // TMP 自动折行不会在源字符串中插入换行符，因此必须在布局前统一设置行距下限。
        // 单行标签不会消费行距；显式 line-height 的但丁笔记仍保持自己的段落节奏。
        float spacing_floor = std::max(2.0f, font_size * 0.12f);
        target_spacing = std::max(target_spacing, spacing_floor);
    }
    invoke_float_setter(g_tmp_set_line_spacing_info, instance, target_spacing);
}

void *prepare_tmp_text(void *instance, void *managed_text) {
    void *replacement = managed_text;
    if (instance != nullptr && managed_text != nullptr && g_string_length != nullptr &&
        g_string_chars != nullptr && g_string_new_utf16 != nullptr) {
        int32_t length = g_string_length(managed_text);
        std::string source;
        if (length >= 0 && length <= 1024 * 1024 &&
            utf16_to_utf8(g_string_chars(managed_text), length, &source)) {
            // TextData formatter hooks can translate before the value reaches
            // TMP_Text.  In that case the setter sees an already-Chinese string
            // and cannot use pointer inequality to know that a fallback font is
            // required.  Prepare the fallback before any non-ASCII text is laid
            // out; it remains fallback-only, so Japanese/Latin primary styling
            // is unchanged.
            bool needs_fallback = std::any_of(source.begin(), source.end(),
                    [](unsigned char byte) { return (byte & 0x80) != 0; });
            if (needs_fallback) ensure_tmp_font();
            replacement = translate_managed_string(managed_text);
            if (replacement != managed_text && !ensure_tmp_font()) {
                return managed_text;
            }
            bool translated = is_translated_managed_string(replacement);
            if (translated && ensure_tmp_font() && g_tmp_get_font != nullptr &&
                g_tmp_set_font != nullptr) {
                void *current_font = g_tmp_get_font(instance, g_tmp_get_font_info);
                TmpComponentState state;
                if (current_font != g_tmp_font_asset) {
                    state = capture_tmp_component_state(instance, current_font);
                    g_tmp_set_font(instance, g_tmp_font_asset, g_tmp_set_font_info);
                    uint64_t switches = g_tmp_primary_font_switches.fetch_add(
                            1, std::memory_order_relaxed) + 1;
                    if (switches == 1 || switches % 500 == 0) {
                        // 周期日志会保留到较晚导出的诊断包，便于确认主字体切换确实发生。
                        LT_LOGI("TMP Chinese primary font switches=%llu font=%p",
                                static_cast<unsigned long long>(switches), g_tmp_font_asset);
                    }
                } else {
                    find_tmp_component_state(instance, &state);
                }
                apply_translated_tmp_style(instance, replacement, state);
            } else if (!translated) {
                restore_tmp_component_state(instance);
            }
            if (replacement != managed_text) {
                uint64_t hits = __atomic_add_fetch(&g_translation_hits, 1, __ATOMIC_RELAXED);
                if (hits == 1 || hits % 500 == 0) {
                    LT_LOGI("TMP translation hits=%llu", static_cast<unsigned long long>(hits));
                }
            }
        }
    }
    return replacement;
}

void replacement_tmp_set_text(void *instance, void *managed_text, const void *method) {
    g_original_tmp_set_text(instance, prepare_tmp_text(instance, managed_text), method);
}

void replacement_tmp_set_text_one(void *instance, void *managed_text, const void *method) {
    g_original_tmp_set_text_one(instance, prepare_tmp_text(instance, managed_text), method);
}

void replacement_ui_set_text(void *instance, void *managed_text, const void *method) {
    g_original_ui_set_text(instance, translate_managed_string(managed_text), method);
}

void replacement_tmp_set_text_string(void *instance, void *managed_text, bool sync_input,
                                     const void *method) {
    g_original_tmp_set_text_string(
            instance, prepare_tmp_text(instance, managed_text), sync_input, method);
}

int32_t replacement_get_language(void *instance, const void *method) {
    uint32_t hits = g_language_hook_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits <= 5 || hits % 5000 == 0) {
        LT_LOGI("Japanese language getter hit=%u source=LocalGameOptionData instance=%p method=%p",
                hits, instance, method);
    }
    return 2;  // LOCALIZE_LANGUAGE.JP
}

int32_t replacement_global_get_language(void *instance, const void *method) {
    uint32_t hits = g_language_hook_hits.fetch_add(1, std::memory_order_relaxed) + 1;
    if (hits <= 5 || hits % 5000 == 0) {
        LT_LOGI("Japanese language getter hit=%u source=GlobalGameManager.Lang instance=%p method=%p",
                hits, instance, method);
    }
    return 2;  // LOCALIZE_LANGUAGE.JP
}

bool read_exact(FILE *file, void *buffer, size_t size) {
    return size == 0 || fread(buffer, 1, size, file) == size;
}

bool read_u32(FILE *file, uint32_t *value) {
    uint32_t encoded = 0;
    if (!read_exact(file, &encoded, sizeof(encoded))) return false;
    *value = ntohl(encoded);
    return true;
}

bool read_string(FILE *file, std::string *value) {
    uint32_t size = 0;
    if (!read_u32(file, &size) || size > kMaxStringBytes) return false;
    value->resize(size);
    return read_exact(file, size == 0 ? nullptr : &(*value)[0], size);
}

bool load_index_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (file == nullptr) {
        LT_LOGW("Index file is not readable path=%s errno=%d", path, errno);
        return false;
    }
    unsigned char magic[sizeof(kIndexMagic)] = {};
    uint32_t schema = 0;
    uint32_t count = 0;
    uint32_t term_count = 0;
    bool valid = read_exact(file, magic, sizeof(magic)) &&
                 memcmp(magic, kIndexMagic, sizeof(magic)) == 0 &&
                 read_u32(file, &schema) && schema == kIndexSchema &&
                 read_u32(file, &count) && count <= kMaxEntries &&
                 read_u32(file, &term_count) && term_count <= kMaxEntries;
    std::unordered_map<std::string, std::string> loaded;
    std::unordered_map<std::string, std::string> loaded_terms;
    if (valid) {
        loaded.reserve(count);
        for (uint32_t i = 0; i < count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded[source] = translation;
        }
        loaded_terms.reserve(term_count);
        for (uint32_t i = 0; valid && i < term_count; ++i) {
            std::string source;
            std::string translation;
            if (!read_string(file, &source) || !read_string(file, &translation) || source.empty()) {
                valid = false;
                break;
            }
            loaded_terms[source] = translation;
        }
    }
    fclose(file);
    if (!valid || loaded.size() != count || loaded_terms.size() != term_count) {
        LT_LOGW("Reject invalid translation index path=%s schema=%u declared=%u/%u loaded=%zu/%zu",
                path, schema, count, term_count, loaded.size(), loaded_terms.size());
        return false;
    }
    g_index.swap(loaded);
    g_terms.swap(loaded_terms);
    rebuild_term_trie();
    LT_LOGI("Loaded Japanese full-text translation index entries=%zu terms=%zu path=%s",
            g_index.size(), g_terms.size(), path);
    return true;
}

bool load_active_index() {
    FILE *pointer = fopen(g_active_pointer, "r");
    if (pointer == nullptr) {
        LT_LOGI("No active translation index pointer path=%s", g_active_pointer);
        return false;
    }
    char index_path[PATH_MAX] = {};
    bool valid = fgets(index_path, sizeof(index_path), pointer) != nullptr;
    fclose(pointer);
    if (!valid) {
        LT_LOGW("Active translation index pointer is empty path=%s", g_active_pointer);
        return false;
    }
    index_path[strcspn(index_path, "\r\n")] = 0;
    if (index_path[0] != '/') {
        LT_LOGW("Reject non-absolute translation index path=%s", index_path);
        return false;
    }
    return load_index_file(index_path);
}

class MappedElfResolver {
public:
    MappedElfResolver() : mapping_(MAP_FAILED), size_(0), base_(0), symbols_(nullptr),
                          symbol_count_(0), strings_(nullptr), strings_size_(0) {}

    ~MappedElfResolver() {
        if (mapping_ != MAP_FAILED) munmap(mapping_, size_);
    }

    bool open(const char *path) {
        FILE *maps = fopen("/proc/self/maps", "r");
        char line[PATH_MAX + 160] = {};
        while (maps != nullptr && fgets(line, sizeof(line), maps) != nullptr) {
            unsigned long start = 0;
            unsigned long offset = 1;
            if (strstr(line, path) != nullptr &&
                sscanf(line, "%lx-%*lx %*s %lx", &start, &offset) == 2 && offset == 0) {
                if (static_cast<uintptr_t>(start) > base_) {
                    base_ = static_cast<uintptr_t>(start);
                }
            }
        }
        if (maps != nullptr) fclose(maps);
        int fd = ::open(path, O_RDONLY | O_CLOEXEC);
        struct stat state = {};
        if (base_ == 0 || fd < 0 || fstat(fd, &state) != 0 || state.st_size <= 0) {
            if (fd >= 0) close(fd);
            return false;
        }
        size_ = static_cast<size_t>(state.st_size);
        mapping_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (mapping_ == MAP_FAILED || size_ < sizeof(Elf64_Ehdr)) return false;
        const Elf64_Ehdr *header = reinterpret_cast<const Elf64_Ehdr *>(mapping_);
        if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
            header->e_ident[EI_CLASS] != ELFCLASS64 || header->e_type != ET_DYN ||
            header->e_shentsize != sizeof(Elf64_Shdr) || header->e_shnum == 0 ||
            header->e_shoff > size_ ||
            header->e_shnum > (size_ - header->e_shoff) / sizeof(Elf64_Shdr)) return false;
        const Elf64_Shdr *sections = reinterpret_cast<const Elf64_Shdr *>(
                reinterpret_cast<const unsigned char *>(mapping_) + header->e_shoff);
        for (size_t i = 0; i < header->e_shnum; ++i) {
            const Elf64_Shdr &section = sections[i];
            if (section.sh_type != SHT_DYNSYM || section.sh_entsize != sizeof(Elf64_Sym) ||
                section.sh_link >= header->e_shnum || section.sh_offset > size_ ||
                section.sh_size > size_ - section.sh_offset) continue;
            const Elf64_Shdr &strings = sections[section.sh_link];
            if (strings.sh_type != SHT_STRTAB || strings.sh_offset > size_ ||
                strings.sh_size > size_ - strings.sh_offset) continue;
            symbols_ = reinterpret_cast<const Elf64_Sym *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + section.sh_offset);
            symbol_count_ = section.sh_size / sizeof(Elf64_Sym);
            strings_ = reinterpret_cast<const char *>(mapping_) + strings.sh_offset;
            strings_size_ = strings.sh_size;
            return true;
        }
        return false;
    }

    template <typename T>
    T resolve(const char *name) const {
        for (size_t i = 0; i < symbol_count_; ++i) {
            const Elf64_Sym &symbol = symbols_[i];
            if (symbol.st_name >= strings_size_ || symbol.st_shndx == SHN_UNDEF) continue;
            if (strcmp(strings_ + symbol.st_name, name) == 0) {
                return reinterpret_cast<T>(base_ + symbol.st_value);
            }
        }
        return nullptr;
    }

    void **resolve_import_slot(const char *name) const {
        if (mapping_ == MAP_FAILED) return nullptr;
        const Elf64_Ehdr *header = reinterpret_cast<const Elf64_Ehdr *>(mapping_);
        const Elf64_Shdr *sections = reinterpret_cast<const Elf64_Shdr *>(
                reinterpret_cast<const unsigned char *>(mapping_) + header->e_shoff);
        for (size_t i = 0; i < header->e_shnum; ++i) {
            const Elf64_Shdr &relocations = sections[i];
            if (relocations.sh_type != SHT_RELA || relocations.sh_entsize != sizeof(Elf64_Rela) ||
                relocations.sh_link >= header->e_shnum || relocations.sh_offset > size_ ||
                relocations.sh_size > size_ - relocations.sh_offset) continue;
            const Elf64_Shdr &symbols = sections[relocations.sh_link];
            if (symbols.sh_type != SHT_DYNSYM || symbols.sh_entsize != sizeof(Elf64_Sym) ||
                symbols.sh_link >= header->e_shnum || symbols.sh_offset > size_ ||
                symbols.sh_size > size_ - symbols.sh_offset) continue;
            const Elf64_Shdr &strings = sections[symbols.sh_link];
            if (strings.sh_type != SHT_STRTAB || strings.sh_offset > size_ ||
                strings.sh_size > size_ - strings.sh_offset) continue;
            const Elf64_Rela *entries = reinterpret_cast<const Elf64_Rela *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + relocations.sh_offset);
            const Elf64_Sym *symbol_table = reinterpret_cast<const Elf64_Sym *>(
                    reinterpret_cast<const unsigned char *>(mapping_) + symbols.sh_offset);
            const char *string_table = reinterpret_cast<const char *>(mapping_) + strings.sh_offset;
            size_t relocation_count = relocations.sh_size / sizeof(Elf64_Rela);
            size_t symbol_count = symbols.sh_size / sizeof(Elf64_Sym);
            for (size_t relocation = 0; relocation < relocation_count; ++relocation) {
                uint32_t type = ELF64_R_TYPE(entries[relocation].r_info);
                size_t symbol_index = ELF64_R_SYM(entries[relocation].r_info);
                if ((type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) ||
                    symbol_index >= symbol_count || symbol_table[symbol_index].st_name >= strings.sh_size) {
                    continue;
                }
                if (strcmp(string_table + symbol_table[symbol_index].st_name, name) == 0) {
                    return reinterpret_cast<void **>(base_ + entries[relocation].r_offset);
                }
            }
        }
        return nullptr;
    }

private:
    void *mapping_;
    size_t size_;
    uintptr_t base_;
    const Elf64_Sym *symbols_;
    size_t symbol_count_;
    const char *strings_;
    size_t strings_size_;
};

bool report_il2cpp_resolver(const MappedElfResolver &resolver, bool query_domain) {
    typedef void *(*domain_get_fn)();
    typedef const void **(*domain_get_assemblies_fn)(const void *, size_t *);
    typedef const void *(*assembly_get_image_fn)(const void *);
    typedef size_t (*image_get_class_count_fn)(const void *);
    typedef void *(*image_get_class_fn)(const void *, size_t);
    typedef const char *(*class_get_name_fn)(void *);
    typedef void *(*class_from_name_fn)(const void *, const char *, const char *);
    typedef const void *(*class_get_method_from_name_fn)(void *, const char *, int);
    typedef const void *(*class_get_methods_fn)(void *, void **);
    typedef const char *(*method_get_name_fn)(const void *);
    typedef uint32_t (*method_get_param_count_fn)(const void *);
    typedef const void *(*method_get_param_fn)(const void *, uint32_t);
    typedef const void *(*method_get_return_type_fn)(const void *);
    typedef char *(*type_get_name_fn)(const void *);
    typedef void (*il2cpp_free_fn)(void *);

    domain_get_fn domain_get = resolver.resolve<domain_get_fn>("il2cpp_domain_get");
    domain_get_assemblies_fn domain_get_assemblies =
            resolver.resolve<domain_get_assemblies_fn>("il2cpp_domain_get_assemblies");
    assembly_get_image_fn assembly_get_image =
            resolver.resolve<assembly_get_image_fn>("il2cpp_assembly_get_image");
    image_get_class_count_fn image_get_class_count =
            resolver.resolve<image_get_class_count_fn>("il2cpp_image_get_class_count");
    image_get_class_fn image_get_class =
            resolver.resolve<image_get_class_fn>("il2cpp_image_get_class");
    class_get_name_fn class_get_name =
            resolver.resolve<class_get_name_fn>("il2cpp_class_get_name");
    class_from_name_fn class_from_name =
            resolver.resolve<class_from_name_fn>("il2cpp_class_from_name");
    class_get_method_from_name_fn class_get_method_from_name =
            resolver.resolve<class_get_method_from_name_fn>("il2cpp_class_get_method_from_name");
    class_get_methods_fn class_get_methods =
            resolver.resolve<class_get_methods_fn>("il2cpp_class_get_methods");
    method_get_name_fn method_get_name =
            resolver.resolve<method_get_name_fn>("il2cpp_method_get_name");
    method_get_param_count_fn method_get_param_count =
            resolver.resolve<method_get_param_count_fn>("il2cpp_method_get_param_count");
    method_get_param_fn method_get_param =
            resolver.resolve<method_get_param_fn>("il2cpp_method_get_param");
    method_get_return_type_fn method_get_return_type =
            resolver.resolve<method_get_return_type_fn>("il2cpp_method_get_return_type");
    type_get_name_fn type_get_name = resolver.resolve<type_get_name_fn>("il2cpp_type_get_name");
    il2cpp_free_fn il2cpp_free = resolver.resolve<il2cpp_free_fn>("il2cpp_free");
    string_length_fn string_length = resolver.resolve<string_length_fn>("il2cpp_string_length");
    string_chars_fn string_chars = resolver.resolve<string_chars_fn>("il2cpp_string_chars");
    string_new_utf16_fn string_new_utf16 =
            resolver.resolve<string_new_utf16_fn>("il2cpp_string_new_utf16");
    runtime_invoke_fn runtime_invoke = resolver.resolve<runtime_invoke_fn>("il2cpp_runtime_invoke");
    object_get_class_fn object_get_class =
            resolver.resolve<object_get_class_fn>("il2cpp_object_get_class");
    object_unbox_fn object_unbox = resolver.resolve<object_unbox_fn>("il2cpp_object_unbox");
    class_get_parent_fn class_get_parent =
            resolver.resolve<class_get_parent_fn>("il2cpp_class_get_parent");
    class_get_fields_fn class_get_fields =
            resolver.resolve<class_get_fields_fn>("il2cpp_class_get_fields");
    field_get_name_fn field_get_name =
            resolver.resolve<field_get_name_fn>("il2cpp_field_get_name");
    field_get_type_fn field_get_type =
            resolver.resolve<field_get_type_fn>("il2cpp_field_get_type");
    field_get_value_fn field_get_value =
            resolver.resolve<field_get_value_fn>("il2cpp_field_get_value");
    field_set_value_fn field_set_value =
            resolver.resolve<field_set_value_fn>("il2cpp_field_set_value");
    if (domain_get == nullptr || domain_get_assemblies == nullptr || assembly_get_image == nullptr ||
        class_from_name == nullptr || class_get_method_from_name == nullptr) {
        LT_LOGW("IL2CPP exports missing domain=%p assemblies=%p image=%p class=%p method=%p",
                reinterpret_cast<void *>(domain_get), reinterpret_cast<void *>(domain_get_assemblies),
                reinterpret_cast<void *>(assembly_get_image), reinterpret_cast<void *>(class_from_name),
                reinterpret_cast<void *>(class_get_method_from_name));
        return false;
    }
    if (!query_domain) return false;
    void *domain = domain_get();
    size_t assembly_count = 0;
    const void **assemblies = domain == nullptr ? nullptr : domain_get_assemblies(domain, &assembly_count);
    if (assemblies == nullptr || assembly_count == 0) return false;
    const void *tmp_method = nullptr;
    const void *ui_method = nullptr;
    const void *tmp_set_font_method = nullptr;
    const void *tmp_get_font_method = nullptr;
    const void *create_tmp_font_method = nullptr;
    const void *create_dynamic_font_method = nullptr;
    const void *tmp_set_text_one_method = nullptr;
    const void *tmp_set_text_string_method = nullptr;
    void *tmp_text_class = nullptr;
    void *ui_text_class = nullptr;
    void *tmp_font_class = nullptr;
    void *unity_font_class = nullptr;
    void *material_class = nullptr;
    void *tmp_settings_class = nullptr;
    const void *get_language_method = nullptr;
    const void *get_global_language_method = nullptr;
    struct AcquisitionTarget {
        std::string name;
        void *klass;
    };
    std::vector<AcquisitionTarget> acquisition_targets;
    std::unordered_set<void *> acquisition_classes;
    for (size_t i = 0; assemblies != nullptr && i < assembly_count; ++i) {
        const void *image = assembly_get_image(assemblies[i]);
        if (image == nullptr) continue;
        if (image_get_class_count != nullptr && image_get_class != nullptr &&
            class_get_name != nullptr) {
            size_t class_count = image_get_class_count(image);
            for (size_t class_index = 0; class_index < class_count; ++class_index) {
                void *klass = image_get_class(image, class_index);
                const char *name = klass == nullptr ? nullptr : class_get_name(klass);
                if (name != nullptr && strncmp(name, "TextData_", 9) == 0 &&
                    acquisition_classes.insert(klass).second) {
                    acquisition_targets.push_back({name, klass});
                }
            }
        }
        if (tmp_method == nullptr) {
            void *klass = class_from_name(image, "TMPro", "TMP_Text");
            if (klass != nullptr) {
                tmp_text_class = klass;
                tmp_method = class_get_method_from_name(klass, "set_text", 1);
            }
        }
        if (ui_method == nullptr) {
            void *klass = class_from_name(image, "UnityEngine.UI", "Text");
            if (klass != nullptr) {
                ui_text_class = klass;
                ui_method = class_get_method_from_name(klass, "set_text", 1);
            }
        }
        if (tmp_font_class == nullptr) {
            tmp_font_class = class_from_name(image, "TMPro", "TMP_FontAsset");
        }
        if (unity_font_class == nullptr) {
            unity_font_class = class_from_name(image, "UnityEngine", "Font");
        }
        if (material_class == nullptr) {
            material_class = class_from_name(image, "UnityEngine", "Material");
        }
        if (tmp_settings_class == nullptr) {
            tmp_settings_class = class_from_name(image, "TMPro", "TMP_Settings");
        }
        if (get_language_method == nullptr) {
            void *klass = class_from_name(image, "LocalSave", "LocalGameOptionData");
            if (klass != nullptr) {
                get_language_method = class_get_method_from_name(klass, "GetLanguage", 0);
            }
        }
        if (get_global_language_method == nullptr) {
            void *klass = class_from_name(image, "", "GlobalGameManager");
            if (klass != nullptr) {
                get_global_language_method = class_get_method_from_name(klass, "get_Lang", 0);
            }
        }
    }
    std::sort(acquisition_targets.begin(), acquisition_targets.end(),
              [](const AcquisitionTarget &left, const AcquisitionTarget &right) {
                  return left.name < right.name;
              });
    LT_LOGI("Discovered TextData formatter classes=%zu", acquisition_targets.size());
    void *tmp_pointer = nullptr;
    void *ui_pointer = nullptr;
    if (tmp_method != nullptr) memcpy(&tmp_pointer, tmp_method, sizeof(tmp_pointer));
    if (ui_method != nullptr) memcpy(&ui_pointer, ui_method, sizeof(ui_pointer));
    LT_LOGI("IL2CPP diagnostic assemblies=%zu TMP_Text.set_text info=%p pointer=%p"
            " UI.Text.set_text info=%p pointer=%p indexEntries=%zu",
            assembly_count, tmp_method, tmp_pointer, ui_method, ui_pointer, g_index.size());
    if (class_get_methods != nullptr && method_get_name != nullptr &&
        method_get_param_count != nullptr && method_get_param != nullptr &&
        type_get_name != nullptr && il2cpp_free != nullptr) {
        struct TargetClass { const char *label; void *klass; };
        TargetClass targets[] = {
                {"TMP_Text", tmp_text_class},
                {"TMP_FontAsset", tmp_font_class},
                {"UnityEngine.Font", unity_font_class},
        };
        const char *interesting[] = {
                "set_font", "get_font", "CreateFontAsset", "CreateDynamicFontFromOSFont",
                "SetText", "SetCharArray"
        };
        for (const TargetClass &target : targets) {
            void *iterator = nullptr;
            const void *method = nullptr;
            while (target.klass != nullptr && (method = class_get_methods(target.klass, &iterator)) != nullptr) {
                const char *method_name = method_get_name(method);
                bool matched = false;
                for (const char *name : interesting) {
                    if (method_name != nullptr && strcmp(method_name, name) == 0) matched = true;
                }
                if (!matched) continue;
                uint32_t parameter_count = method_get_param_count(method);
                std::string signature;
                for (uint32_t parameter = 0; parameter < parameter_count; ++parameter) {
                    char *type_name = type_get_name(method_get_param(method, parameter));
                    if (parameter > 0) signature += ",";
                    signature += type_name == nullptr ? "?" : type_name;
                    if (type_name != nullptr) il2cpp_free(type_name);
                }
                void *pointer = nullptr;
                memcpy(&pointer, method, sizeof(pointer));
                LT_LOGI("IL2CPP method inventory class=%s method=%s params=(%s) info=%p pointer=%p",
                        target.label, method_name, signature.c_str(), method, pointer);
                if (target.klass == tmp_font_class && strcmp(method_name, "CreateFontAsset") == 0 &&
                    signature.find("System.String,") == 0 && parameter_count == 9) {
                    create_tmp_font_method = method;
                }
                if (target.klass == unity_font_class &&
                    strcmp(method_name, "CreateDynamicFontFromOSFont") == 0 &&
                    signature == "System.String,System.Int32") {
                    create_dynamic_font_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.String") {
                    tmp_set_text_one_method = method;
                }
                if (target.klass == tmp_text_class && strcmp(method_name, "SetText") == 0 &&
                    signature == "System.String,System.Boolean") {
                    tmp_set_text_string_method = method;
                }
            }
        }
    }
    if (tmp_text_class != nullptr) {
        tmp_set_font_method = class_get_method_from_name(tmp_text_class, "set_font", 1);
        tmp_get_font_method = class_get_method_from_name(tmp_text_class, "get_font", 0);
    }
    auto find_method_by_signature = [&](void *klass, const char *expected_name,
                                        const char *expected_signature) -> const void * {
        if (klass == nullptr || class_get_methods == nullptr || method_get_name == nullptr ||
            method_get_param_count == nullptr || method_get_param == nullptr ||
            type_get_name == nullptr || il2cpp_free == nullptr) {
            return nullptr;
        }
        void *iterator = nullptr;
        const void *method = nullptr;
        while ((method = class_get_methods(klass, &iterator)) != nullptr) {
            const char *method_name = method_get_name(method);
            if (method_name == nullptr || strcmp(method_name, expected_name) != 0) continue;
            std::string signature;
            uint32_t parameter_count = method_get_param_count(method);
            for (uint32_t parameter = 0; parameter < parameter_count; ++parameter) {
                char *type_name = type_get_name(method_get_param(method, parameter));
                if (parameter > 0) signature += ",";
                signature += type_name == nullptr ? "?" : type_name;
                if (type_name != nullptr) il2cpp_free(type_name);
            }
            if (signature == expected_signature) return method;
        }
        return nullptr;
    };
    const void *tmp_get_font_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontMaterial", 0);
    const void *tmp_get_shared_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSharedMaterial", 0);
    const void *tmp_set_shared_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSharedMaterial", 1);
    const void *tmp_get_line_spacing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_lineSpacing", 0);
    const void *tmp_set_line_spacing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_lineSpacing", 1);
    const void *tmp_get_font_size_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSize", 0);
    const void *material_get_float_method = find_method_by_signature(
            material_class, "GetFloat", "System.String");
    const void *material_set_float_method = find_method_by_signature(
            material_class, "SetFloat", "System.String,System.Single");
    const void *material_get_color_method = find_method_by_signature(
            material_class, "GetColor", "System.String");
    const void *material_set_color_method = find_method_by_signature(
            material_class, "SetColor", "System.String,UnityEngine.Color");
    const void *get_fallback_fonts_method = tmp_settings_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_settings_class, "get_fallbackFontAssets", 0);
    LT_LOGI("TMP fallback getter class=%p method=%p", tmp_settings_class, get_fallback_fonts_method);
    LT_LOGI("TMP style methods material=%p fontMaterial=%p shared=%p/%p spacing=%p/%p"
            " fontSize=%p outline=%p/%p/%p/%p",
            material_class, tmp_get_font_material_method, tmp_get_shared_material_method,
            tmp_set_shared_material_method, tmp_get_line_spacing_method,
            tmp_set_line_spacing_method, tmp_get_font_size_method,
            material_get_float_method, material_set_float_method,
            material_get_color_method, material_set_color_method);
    if (tmp_font_class != nullptr && create_tmp_font_method == nullptr) {
        create_tmp_font_method = class_get_method_from_name(tmp_font_class, "CreateFontAsset", 9);
    }
    if (unity_font_class != nullptr && create_dynamic_font_method == nullptr) {
        create_dynamic_font_method =
                class_get_method_from_name(unity_font_class, "CreateDynamicFontFromOSFont", 2);
    }
    void *tmp_set_font_pointer = nullptr;
    void *tmp_get_font_pointer = nullptr;
    void *create_tmp_font_pointer = nullptr;
    void *create_dynamic_font_pointer = nullptr;
    void *get_language_pointer = nullptr;
    void *get_global_language_pointer = nullptr;
    if (tmp_set_font_method != nullptr) memcpy(&tmp_set_font_pointer, tmp_set_font_method, sizeof(void *));
    if (tmp_get_font_method != nullptr) memcpy(&tmp_get_font_pointer, tmp_get_font_method, sizeof(void *));
    if (create_tmp_font_method != nullptr) memcpy(&create_tmp_font_pointer, create_tmp_font_method, sizeof(void *));
    if (create_dynamic_font_method != nullptr) memcpy(&create_dynamic_font_pointer, create_dynamic_font_method, sizeof(void *));
    if (get_language_method != nullptr) memcpy(&get_language_pointer, get_language_method, sizeof(void *));
    if (get_global_language_method != nullptr) {
        memcpy(&get_global_language_pointer, get_global_language_method, sizeof(void *));
    }
    bool hook_ready = tmp_pointer != nullptr && tmp_set_font_pointer != nullptr &&
            tmp_get_font_pointer != nullptr &&
            create_tmp_font_pointer != nullptr && create_dynamic_font_pointer != nullptr &&
            get_global_language_pointer != nullptr &&
            string_length != nullptr && string_chars != nullptr && string_new_utf16 != nullptr &&
            runtime_invoke != nullptr && object_get_class != nullptr && object_unbox != nullptr;
    if (hook_ready && !g_hook_installed) {
        g_string_length = string_length;
        g_string_chars = string_chars;
        g_string_new_utf16 = string_new_utf16;
        g_runtime_invoke = runtime_invoke;
        g_object_get_class = object_get_class;
        g_object_unbox = object_unbox;
        g_class_get_method = class_get_method_from_name;
        g_class_get_parent = class_get_parent;
        g_class_get_fields = class_get_fields;
        g_field_get_name = field_get_name;
        g_field_get_type = field_get_type;
        g_field_get_value = field_get_value;
        g_field_set_value = field_set_value;
        g_type_get_name = type_get_name;
        g_il2cpp_free = il2cpp_free;
        g_create_dynamic_font = reinterpret_cast<create_dynamic_font_fn>(create_dynamic_font_pointer);
        g_create_tmp_font = reinterpret_cast<create_tmp_font_fn>(create_tmp_font_pointer);
        g_tmp_set_font = reinterpret_cast<tmp_set_font_fn>(tmp_set_font_pointer);
        g_tmp_get_font = reinterpret_cast<tmp_get_font_fn>(tmp_get_font_pointer);
        g_create_dynamic_font_info = create_dynamic_font_method;
        g_create_tmp_font_info = create_tmp_font_method;
        g_tmp_set_font_info = tmp_set_font_method;
        g_tmp_get_font_info = tmp_get_font_method;
        g_get_fallback_fonts_info = get_fallback_fonts_method;
        g_tmp_get_font_material_info = tmp_get_font_material_method;
        g_tmp_get_shared_material_info = tmp_get_shared_material_method;
        g_tmp_set_shared_material_info = tmp_set_shared_material_method;
        g_tmp_get_line_spacing_info = tmp_get_line_spacing_method;
        g_tmp_set_line_spacing_info = tmp_set_line_spacing_method;
        g_tmp_get_font_size_info = tmp_get_font_size_method;
        g_material_get_float_info = material_get_float_method;
        g_material_set_float_info = material_set_float_method;
        g_material_get_color_info = material_get_color_method;
        g_material_set_color_info = material_set_color_method;
        MSHookFunction(tmp_pointer, reinterpret_cast<void *>(replacement_tmp_set_text),
                       reinterpret_cast<void **>(&g_original_tmp_set_text));
        auto has_inline_hook_space = [&](void *klass, void *pointer) {
            if (klass == nullptr || pointer == nullptr || class_get_methods == nullptr) return false;
            std::vector<uintptr_t> pointers;
            void *iterator = nullptr;
            const void *candidate = nullptr;
            while ((candidate = class_get_methods(klass, &iterator)) != nullptr) {
                void *candidate_pointer = nullptr;
                memcpy(&candidate_pointer, candidate, sizeof(candidate_pointer));
                if (candidate_pointer != nullptr) {
                    pointers.push_back(reinterpret_cast<uintptr_t>(candidate_pointer));
                }
            }
            std::sort(pointers.begin(), pointers.end());
            pointers.erase(std::unique(pointers.begin(), pointers.end()), pointers.end());
            uintptr_t address = reinterpret_cast<uintptr_t>(pointer);
            auto position = std::lower_bound(pointers.begin(), pointers.end(), address);
            return position != pointers.end() && *position == address &&
                    (position + 1 == pointers.end() || *(position + 1) - address >= 16);
        };
        if (ui_pointer != nullptr && has_inline_hook_space(ui_text_class, ui_pointer)) {
            MSHookFunction(ui_pointer, reinterpret_cast<void *>(replacement_ui_set_text),
                           reinterpret_cast<void **>(&g_original_ui_set_text));
        } else if (ui_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent UI.Text.set_text pointer=%p", ui_pointer);
        }
        void *set_text_one_pointer = nullptr;
        if (tmp_set_text_one_method != nullptr) {
            memcpy(&set_text_one_pointer, tmp_set_text_one_method, sizeof(void *));
            MSHookFunction(set_text_one_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_text_one),
                           reinterpret_cast<void **>(&g_original_tmp_set_text_one));
        }
        void *set_text_string_pointer = nullptr;
        if (tmp_set_text_string_method != nullptr) {
            memcpy(&set_text_string_pointer, tmp_set_text_string_method, sizeof(void *));
            MSHookFunction(set_text_string_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_text_string),
                           reinterpret_cast<void **>(&g_original_tmp_set_text_string));
        }
        if (get_language_pointer != nullptr) {
            MSHookFunction(get_language_pointer,
                           reinterpret_cast<void *>(replacement_get_language),
                           reinterpret_cast<void **>(&g_original_get_language));
        }
        MSHookFunction(get_global_language_pointer,
                       reinterpret_cast<void *>(replacement_global_get_language),
                       reinterpret_cast<void **>(&g_original_global_get_language));
        size_t acquisition_counts[] = {0, 0, 0};
        std::unordered_set<void *> acquisition_pointers;
        bool acquisition_ready = class_get_methods != nullptr && method_get_name != nullptr &&
                method_get_param_count != nullptr && method_get_return_type != nullptr &&
                type_get_name != nullptr && il2cpp_free != nullptr &&
                class_get_parent != nullptr && class_get_fields != nullptr &&
                field_get_name != nullptr && field_get_type != nullptr &&
                field_get_value != nullptr && field_set_value != nullptr;
        if (acquisition_ready) {
            for (const AcquisitionTarget &target : acquisition_targets) {
                void *iterator = nullptr;
                const void *method = nullptr;
                while (target.klass != nullptr &&
                       (method = class_get_methods(target.klass, &iterator)) != nullptr) {
                    const char *method_name = method_get_name(method);
                    if (method_name == nullptr ||
                        (strncmp(method_name, "Get", 3) != 0 &&
                         strncmp(method_name, "get_", 4) != 0)) {
                        continue;
                    }
                    char *return_name = type_get_name(method_get_return_type(method));
                    bool returns_string = return_name != nullptr &&
                            strcmp(return_name, "System.String") == 0;
                    if (return_name != nullptr) il2cpp_free(return_name);
                    uint32_t parameter_count = method_get_param_count(method);
                    // Argument-bearing TextData getters are the game's formatting
                    // boundary: they resolve placeholders and nested description
                    // objects before the value is decorated for TMP.  Discover all
                    // of them instead of growing a page-by-page class whitelist.
                    bool selected_formatter = parameter_count >= 1 && parameter_count <= 2;
                    // Tiny direct getters are frequently only one or two ARM64
                    // instructions and sit 4-8 bytes apart.  The legacy inline
                    // hook relocates a wider prologue, so only hook the verified
                    // formatter bodies here; exact direct values remain covered
                    // by the TMP/UI fallback.
                    if (!returns_string || !selected_formatter) continue;
                    void *pointer = nullptr;
                    memcpy(&pointer, method, sizeof(pointer));
                    if (pointer == nullptr || acquisition_pointers.count(pointer) != 0 ||
                        acquisition_counts[parameter_count] >= kAcquisitionSlots) {
                        continue;
                    }
                    if (!has_inline_hook_space(target.klass, pointer)) {
                        LT_LOGW("Skip unsafe adjacent text formatter class=%s method=%s pointer=%p",
                                target.name.c_str(), method_name, pointer);
                        continue;
                    }
                    size_t slot = acquisition_counts[parameter_count];
                    if (parameter_count == 0) {
                        MSHookFunction(pointer,
                                reinterpret_cast<void *>(kAcquisition0Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition0[slot]));
                        if (g_acquisition0[slot] == nullptr) continue;
                    } else if (parameter_count == 1) {
                        MSHookFunction(pointer,
                                reinterpret_cast<void *>(kAcquisition1Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition1[slot]));
                        if (g_acquisition1[slot] == nullptr) continue;
                    } else {
                        MSHookFunction(pointer,
                                reinterpret_cast<void *>(kAcquisition2Replacements[slot]),
                                reinterpret_cast<void **>(&g_acquisition2[slot]));
                        if (g_acquisition2[slot] == nullptr) continue;
                    }
                    acquisition_pointers.insert(pointer);
                    acquisition_counts[parameter_count]++;
                    LT_LOGI("Text acquisition hook class=%s method=%s argc=%u pointer=%p",
                            target.name.c_str(), method_name, parameter_count, pointer);
                }
            }
        }
        g_hook_installed = g_original_tmp_set_text != nullptr;
        LT_LOGI("TMP translation hook installed=%d setter=%p/%p SetText1=%p/%p SetText2=%p/%p",
                g_hook_installed, tmp_pointer, reinterpret_cast<void *>(g_original_tmp_set_text),
                set_text_one_pointer, reinterpret_cast<void *>(g_original_tmp_set_text_one),
                set_text_string_pointer, reinterpret_cast<void *>(g_original_tmp_set_text_string));
        LT_LOGI("UI.Text translation hook setter=%p/%p",
                ui_pointer, reinterpret_cast<void *>(g_original_ui_set_text));
        LT_LOGI("Japanese language hooks installed option=%p/%p global=%p/%p",
                get_language_pointer, reinterpret_cast<void *>(g_original_get_language),
                get_global_language_pointer,
                reinterpret_cast<void *>(g_original_global_get_language));
        LT_LOGI("Text acquisition hooks installed argc0=%zu argc1=%zu argc2=%zu ready=%d",
                acquisition_counts[0], acquisition_counts[1], acquisition_counts[2],
                acquisition_ready);
    } else if (!hook_ready) {
        LT_LOGW("TMP translation hook prerequisites missing text=%p setFont=%p createTmp=%p"
                " createDynamic=%p language=%p/%p strings=%p/%p/%p invoke=%p",
                tmp_pointer, tmp_set_font_pointer, create_tmp_font_pointer,
                create_dynamic_font_pointer, get_language_pointer, get_global_language_pointer,
                reinterpret_cast<void *>(string_length),
                reinterpret_cast<void *>(string_chars), reinterpret_cast<void *>(string_new_utf16),
                reinterpret_cast<void *>(runtime_invoke));
    }
    return tmp_method != nullptr && tmp_pointer != nullptr;
}

void *replacement_il2cpp_init(const char *domain_name) {
    void *domain = g_original_il2cpp_init(domain_name);
    MappedElfResolver resolver;
    bool resolved = resolver.open(g_il2cpp_path) && report_il2cpp_resolver(resolver, true);
    pthread_mutex_lock(&g_lock);
    g_resolver_reported = resolved;
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("IL2CPP init boundary domain=%p translationReady=%d path=%s",
            domain, resolved, g_il2cpp_path);
    return domain;
}

void *replacement_unity_dlsym(void *handle, const char *name) {
    void *resolved = g_original_unity_dlsym(handle, name);
    if (name != nullptr && strcmp(name, "il2cpp_init") == 0 && resolved != nullptr) {
        g_original_il2cpp_init = reinterpret_cast<il2cpp_init_fn>(resolved);
        LT_LOGI("Unity requested il2cpp_init original=%p replacement=%p",
                resolved, reinterpret_cast<void *>(replacement_il2cpp_init));
        return reinterpret_cast<void *>(replacement_il2cpp_init);
    }
    return resolved;
}

bool install_global_dlsym_lifecycle_hook() {
    pthread_mutex_lock(&g_lock);
    if (g_probe_started) {
        pthread_mutex_unlock(&g_lock);
        return true;
    }
    pthread_mutex_unlock(&g_lock);
    void *target = dlsym(RTLD_DEFAULT, "dlsym");
    if (target == nullptr) return false;
    MSHookFunction(target, reinterpret_cast<void *>(replacement_unity_dlsym),
                   reinterpret_cast<void **>(&g_original_unity_dlsym));
    if (g_original_unity_dlsym == nullptr) {
        LT_LOGW("Unable to hook dlsym lifecycle target=%p", target);
        return false;
    }
    pthread_mutex_lock(&g_lock);
    g_probe_started = true;
    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    if (real_lib_dir != nullptr) {
        snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s/libil2cpp.so", real_lib_dir);
    }
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("Hooked dlsym lifecycle target=%p trampoline=%p",
            target, reinterpret_cast<void *>(g_original_unity_dlsym));
    return true;
}

int mapping_protection(void *address) {
    FILE *maps = fopen("/proc/self/maps", "r");
    char line[PATH_MAX + 160] = {};
    uintptr_t target = reinterpret_cast<uintptr_t>(address);
    int protection = PROT_READ;
    while (maps != nullptr && fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long start = 0;
        unsigned long end = 0;
        char permissions[5] = {};
        if (sscanf(line, "%lx-%lx %4s", &start, &end, permissions) == 3 &&
            target >= start && target < end) {
            protection = 0;
            if (permissions[0] == 'r') protection |= PROT_READ;
            if (permissions[1] == 'w') protection |= PROT_WRITE;
            if (permissions[2] == 'x') protection |= PROT_EXEC;
            break;
        }
    }
    if (maps != nullptr) fclose(maps);
    return protection;
}

bool install_unity_dlsym_redirect(const char *il2cpp_path) {
    if (il2cpp_path == nullptr) return false;
    pthread_mutex_lock(&g_lock);
    if (g_probe_started) {
        pthread_mutex_unlock(&g_lock);
        return true;
    }
    pthread_mutex_unlock(&g_lock);
    std::string unity_path(il2cpp_path);
    size_t slash = unity_path.rfind('/');
    if (slash == std::string::npos) return false;
    unity_path.replace(slash + 1, std::string::npos, "libunity.so");
    MappedElfResolver resolver;
    if (!resolver.open(unity_path.c_str())) return false;
    void **slot = resolver.resolve_import_slot("dlsym");
    if (slot == nullptr || *slot == nullptr) return false;
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(page_size) - 1);
    int original_protection = mapping_protection(slot);
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                 original_protection | PROT_WRITE) != 0) {
        LT_LOGW("Unable to write Unity dlsym import slot=%p errno=%d", slot, errno);
        return false;
    }
    g_original_unity_dlsym = reinterpret_cast<dlsym_fn>(*slot);
    __atomic_store_n(slot, reinterpret_cast<void *>(replacement_unity_dlsym), __ATOMIC_RELEASE);
    mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size), original_protection);
    pthread_mutex_lock(&g_lock);
    g_probe_started = true;
    snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s", il2cpp_path);
    pthread_mutex_unlock(&g_lock);
    LT_LOGI("Redirected Unity dlsym import slot=%p original=%p replacement=%p",
            slot, reinterpret_cast<void *>(g_original_unity_dlsym),
            reinterpret_cast<void *>(replacement_unity_dlsym));
    return true;
}

}  // namespace

void configure_limbus_translation_runtime(const char *active_index_pointer) {
    if (active_index_pointer == nullptr || active_index_pointer[0] != '/') {
        LT_LOGW("Reject invalid active index pointer");
        return;
    }
    pthread_mutex_lock(&g_lock);
    snprintf(g_active_pointer, sizeof(g_active_pointer), "%s", active_index_pointer);
    g_configured = true;
    g_resolver_reported = false;
    g_probe_started = false;
    g_il2cpp_path[0] = 0;
    g_index.clear();
    g_terms.clear();
    g_term_trie.clear();
    load_active_index();
    pthread_mutex_unlock(&g_lock);
}

void activate_limbus_translation_lifecycle_hook() {
    if (!g_configured) return;
    install_global_dlsym_lifecycle_hook();
}

void on_limbus_translation_library_loaded(const char *name, void *handle) {
    if (handle != nullptr) probe_limbus_translation_runtime(name);
}

void probe_limbus_translation_runtime(const char *library_path) {
    if (!g_configured || library_path == nullptr || library_path[0] != '/' ||
        strstr(library_path, "libil2cpp.so") == nullptr) return;
    pthread_mutex_lock(&g_lock);
    snprintf(g_il2cpp_path, sizeof(g_il2cpp_path), "%s", library_path);
    pthread_mutex_unlock(&g_lock);
}
