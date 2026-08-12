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
const uint32_t kIndexSchema = 8;
const uint32_t kMaxEntries = 1000000;
const uint32_t kMaxStringBytes = 16 * 1024 * 1024;

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_font_state_lock = PTHREAD_MUTEX_INITIALIZER;
char g_active_pointer[PATH_MAX] = {};
std::unordered_map<std::string, std::string> g_index;
std::unordered_map<std::string, std::string> g_terms;
// 部分人格列表会把汉化包标题中的换行压成空格或直接移除；该索引只保存由包内译文
// 推导出的原始分行，让运行时兼容不同页面，而无需硬编码任何人格名称或译文。
std::unordered_map<std::string, std::string> g_package_line_breaks;
struct TermTrieNode {
    std::unordered_map<unsigned char, size_t> children;
    const std::string *source = nullptr;
    const std::string *translation = nullptr;
};
std::vector<TermTrieNode> g_term_trie;
bool g_configured = false;
bool g_resolver_reported = false;
bool g_probe_started = false;
bool g_hook_installed = false;
bool g_font_attempted = false;
void *g_tmp_font_asset = nullptr;
void *g_ui_font = nullptr;
// 译文来源按 UTF-8 内容登记，避免游戏复制 IL2CPP String 后丢失仅依赖对象地址的字体标记。
std::unordered_set<std::string> g_translated_managed_texts;
struct TmpComponentState {
    void *font = nullptr;
    void *shared_material = nullptr;
    float line_spacing = 0.0f;
    bool has_line_spacing = false;
    float font_size = 0.0f;
    bool has_font_size = false;
    float font_size_min = 0.0f;
    bool has_font_size_min = false;
    float font_size_max = 0.0f;
    bool has_font_size_max = false;
    bool auto_sizing = false;
    bool has_auto_sizing = false;
    bool word_wrapping = false;
    bool has_word_wrapping = false;
    int32_t overflow_mode = 0;
    bool has_overflow_mode = false;
};
std::unordered_map<void *, TmpComponentState> g_original_tmp_states;
struct UiComponentState {
    void *font = nullptr;
    float line_spacing = 1.0f;
    bool has_line_spacing = false;
    int32_t font_size = 0;
    bool has_font_size = false;
    int32_t resize_min_size = 0;
    bool has_resize_min_size = false;
    int32_t resize_max_size = 0;
    bool has_resize_max_size = false;
    bool resize_best_fit = false;
    bool has_resize_best_fit = false;
    int32_t horizontal_overflow = 0;
    bool has_horizontal_overflow = false;
    int32_t vertical_overflow = 0;
    bool has_vertical_overflow = false;
};
std::unordered_map<void *, UiComponentState> g_original_ui_states;
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
typedef void (*tmp_set_material_fn)(void *, void *, const void *);
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
tmp_set_material_fn g_original_tmp_set_shared_material = nullptr;
tmp_set_material_fn g_original_tmp_set_material = nullptr;
tmp_set_text_fn g_original_tmp_set_text = nullptr;
tmp_set_text_fn g_original_tmp_set_text_one = nullptr;
tmp_set_text_fn g_original_ui_set_text = nullptr;
tmp_set_text_string_fn g_original_tmp_set_text_string = nullptr;
const void *g_create_dynamic_font_info = nullptr;
const void *g_create_tmp_font_info = nullptr;
const void *g_tmp_set_font_info = nullptr;
const void *g_tmp_get_font_info = nullptr;
const void *g_get_fallback_fonts_info = nullptr;
const void *g_tmp_font_get_material_info = nullptr;
const void *g_tmp_get_font_material_info = nullptr;
const void *g_tmp_get_shared_material_info = nullptr;
const void *g_tmp_set_shared_material_info = nullptr;
const void *g_tmp_get_line_spacing_info = nullptr;
const void *g_tmp_set_line_spacing_info = nullptr;
const void *g_tmp_get_font_size_info = nullptr;
const void *g_tmp_set_font_size_info = nullptr;
const void *g_tmp_get_font_size_min_info = nullptr;
const void *g_tmp_set_font_size_min_info = nullptr;
const void *g_tmp_get_font_size_max_info = nullptr;
const void *g_tmp_set_font_size_max_info = nullptr;
const void *g_tmp_get_auto_sizing_info = nullptr;
const void *g_tmp_set_auto_sizing_info = nullptr;
const void *g_tmp_get_word_wrapping_info = nullptr;
const void *g_tmp_set_word_wrapping_info = nullptr;
const void *g_tmp_get_overflow_mode_info = nullptr;
const void *g_tmp_set_overflow_mode_info = nullptr;
const void *g_tmp_get_rect_transform_info = nullptr;
const void *g_ui_get_font_info = nullptr;
const void *g_ui_set_font_info = nullptr;
const void *g_ui_get_line_spacing_info = nullptr;
const void *g_ui_set_line_spacing_info = nullptr;
const void *g_ui_get_font_size_info = nullptr;
const void *g_ui_set_font_size_info = nullptr;
const void *g_ui_get_resize_min_size_info = nullptr;
const void *g_ui_set_resize_min_size_info = nullptr;
const void *g_ui_get_resize_max_size_info = nullptr;
const void *g_ui_set_resize_max_size_info = nullptr;
const void *g_ui_get_resize_best_fit_info = nullptr;
const void *g_ui_set_resize_best_fit_info = nullptr;
const void *g_ui_get_horizontal_overflow_info = nullptr;
const void *g_ui_set_horizontal_overflow_info = nullptr;
const void *g_ui_get_vertical_overflow_info = nullptr;
const void *g_ui_set_vertical_overflow_info = nullptr;
const void *g_ui_get_rect_transform_info = nullptr;
const void *g_rect_transform_get_rect_info = nullptr;
const void *g_object_get_name_info = nullptr;
const void *g_component_get_transform_info = nullptr;
const void *g_transform_get_parent_info = nullptr;
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
std::atomic<uint64_t> g_ui_primary_font_switches{0};
std::atomic<uint64_t> g_layout_decisions{0};

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

bool contains_japanese_kana(const std::string &value) {
    std::u16string utf16;
    if (!utf8_to_utf16(value, &utf16)) return false;
    return std::any_of(utf16.begin(), utf16.end(), [](char16_t character) {
        // 覆盖平假名、片假名、片假名扩展与半角片假名。日文汉字与中文共用码位，
        // 不能单凭汉字判定，否则会误伤“振動 -> 震颤”一类合法术语。
        return (character >= 0x3040 && character <= 0x30ff) ||
               (character >= 0x31f0 && character <= 0x31ff) ||
               (character >= 0xff66 && character <= 0xff9f);
    });
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
        if (!is_usable_display_text(entry.first) ||
            !is_usable_display_text(entry.second) ||
            entry.first == entry.second) {
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
        g_term_trie[node].source = &entry.first;
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
        const std::string *best_source = nullptr;
        const std::string *best = nullptr;
        for (size_t cursor = offset; cursor < source.size(); ++cursor) {
            unsigned char byte = static_cast<unsigned char>(source[cursor]);
            auto child = g_term_trie[node].children.find(byte);
            if (child == g_term_trie[node].children.end()) break;
            node = child->second;
            if (g_term_trie[node].translation != nullptr) {
                best_source = g_term_trie[node].source;
                best = g_term_trie[node].translation;
                best_end = cursor + 1;
            }
        }
        if (best != nullptr && best_source != nullptr) {
            // “呼吸 -> 呼吸法”“以上 -> 或以上”这类包内正式词条必须保留。
            // 若当前命中本来就位于完整译文内部，则跳过，保证对象获取层与 TMP 层
            // 多次经过同一字符串时不会不断追加前后缀。
            size_t translated_offset = best->find(*best_source);
            bool already_translated = false;
            while (translated_offset != std::string::npos) {
                if (offset >= translated_offset) {
                    size_t translated_start = offset - translated_offset;
                    if (source.compare(translated_start, best->size(), *best) == 0) {
                        already_translated = true;
                        break;
                    }
                }
                translated_offset = best->find(*best_source, translated_offset + 1);
            }
            if (already_translated) {
                best = nullptr;
                best_source = nullptr;
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
    // 短术语只作为格式化后文本的补充通道。若替换后仍残留假名，说明当前文本
    // 只命中了局部词条；拒绝输出半中文半日文的碎片，等待完整映射或后续词表覆盖。
    if (changed && contains_japanese_kana(source) && contains_japanese_kana(*output)) {
        output->clear();
        return false;
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

std::string apply_uniform_line_height(const std::string &text, int32_t percentage) {
    std::string output;
    output.reserve(text.size() + 96);
    std::string line_height_tag = "<line-height=" + std::to_string(percentage) + "%>\n";
    for (char character : text) {
        if (character != '\n') {
            output.push_back(character);
            continue;
        }
        // TMP 在换行符前读取当前 line-height；换行后立即恢复 100%，避免标签
        // 继续影响同一组件之外的自动布局计算。
        output += line_height_tag;
        output += "<line-height=100%>";
    }
    return output;
}

float personality_title_character_width(uint32_t codepoint) {
    // 拉丁字母、数字与标点在当前中文字体中的实际宽度明显小于一个汉字；
    // 用近似字宽而不是字节数计算，避免把 LCA、E.G.O 等缩写过度拆行。
    if (codepoint == ' ') return 0.35f;
    if (codepoint <= 0x7f) return 0.55f;
    return 1.0f;
}

std::vector<std::string> split_utf8_codepoints(const std::string &text) {
    std::vector<std::string> codepoints;
    for (size_t offset = 0; offset < text.size();) {
        unsigned char lead = static_cast<unsigned char>(text[offset]);
        size_t length = 1;
        if ((lead & 0xe0) == 0xc0) length = 2;
        else if ((lead & 0xf0) == 0xe0) length = 3;
        else if ((lead & 0xf8) == 0xf0) length = 4;
        if (offset + length > text.size()) length = 1;
        codepoints.emplace_back(text.substr(offset, length));
        offset += length;
    }
    return codepoints;
}

uint32_t first_utf8_codepoint(const std::string &text) {
    if (text.empty()) return 0;
    const unsigned char *bytes = reinterpret_cast<const unsigned char *>(text.data());
    if ((bytes[0] & 0x80) == 0) return bytes[0];
    if ((bytes[0] & 0xe0) == 0xc0 && text.size() >= 2) {
        return ((bytes[0] & 0x1f) << 6) | (bytes[1] & 0x3f);
    }
    if ((bytes[0] & 0xf0) == 0xe0 && text.size() >= 3) {
        return ((bytes[0] & 0x0f) << 12) | ((bytes[1] & 0x3f) << 6) |
               (bytes[2] & 0x3f);
    }
    if ((bytes[0] & 0xf8) == 0xf0 && text.size() >= 4) {
        return ((bytes[0] & 0x07) << 18) | ((bytes[1] & 0x3f) << 12) |
               ((bytes[2] & 0x3f) << 6) | (bytes[3] & 0x3f);
    }
    return bytes[0];
}

std::vector<std::string> wrap_personality_title_line(const std::string &line,
                                                     float maximum_width) {
    float total_width = 0.0f;
    for (const std::string &codepoint : split_utf8_codepoints(line)) {
        total_width += personality_title_character_width(first_utf8_codepoint(codepoint));
    }
    // 汉化包分行拥有最高优先级：未超宽的原始行原样保留；超宽但没有空格的行
    // 也不按汉字硬拆，避免运行时自行改变译者确定的名称结构。
    if (total_width <= maximum_width || line.find(' ') == std::string::npos) {
        return {line};
    }
    std::vector<std::string> result;
    std::vector<std::string> codepoints = split_utf8_codepoints(line);
    size_t start = 0;
    while (start < codepoints.size()) {
        float width = 0.0f;
        size_t end = start;
        size_t last_space = std::string::npos;
        while (end < codepoints.size()) {
            uint32_t codepoint = first_utf8_codepoint(codepoints[end]);
            float next_width = personality_title_character_width(codepoint);
            if (end > start && width + next_width > maximum_width) break;
            width += next_width;
            if (codepoint == ' ') last_space = end;
            ++end;
        }
        if (end < codepoints.size() && last_space != std::string::npos && last_space > start) {
            end = last_space;
        } else if (end < codepoints.size()) {
            // 当前剩余片段找不到可用空格时保留整段，不在汉字中间制造新换行。
            end = codepoints.size();
        }
        std::string wrapped;
        for (size_t index = start; index < end; ++index) wrapped += codepoints[index];
        while (!wrapped.empty() && wrapped.back() == ' ') wrapped.pop_back();
        if (!wrapped.empty()) result.push_back(wrapped);
        start = end;
        while (start < codepoints.size() && codepoints[start] == " ") ++start;
    }
    if (result.empty()) result.push_back(line);
    return result;
}

std::string reflow_personality_title(const std::string &text, size_t *line_count) {
    std::vector<std::string> lines;
    size_t start = 0;
    while (start <= text.size()) {
        size_t end = text.find('\n', start);
        std::string line = text.substr(start, end == std::string::npos ?
                std::string::npos : end - start);
        // 230 宽、35 字号的人格卡可稳定容纳约 8.4 个等宽汉字；优先在包内空格处断行。
        std::vector<std::string> wrapped = wrap_personality_title_line(line, 8.4f);
        lines.insert(lines.end(), wrapped.begin(), wrapped.end());
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (line_count != nullptr) *line_count = lines.size();
    std::string output;
    for (size_t index = 0; index < lines.size(); ++index) {
        if (index > 0) output.push_back('\n');
        output += lines[index];
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
        // 少数旧界面仍使用 UnityEngine.UI.Text，TMP_FontAsset 无法直接赋给它们。
        // Android 的 sans-serif 是系统复合字体族，会按字符回退到设备内置中文字体，
        // 因而可作为旧文本组件的稳定中文主字体，避免继续采样原日文字体的缺字方块。
        void *ui_exception = nullptr;
        void *ui_font_name = new_managed_string("sans-serif");
        int32_t ui_font_size = 32;
        void *ui_font_args[] = {ui_font_name, &ui_font_size};
        if (ui_font_name != nullptr && g_create_dynamic_font_info != nullptr &&
            g_runtime_invoke != nullptr) {
            g_ui_font = g_runtime_invoke(
                    g_create_dynamic_font_info, nullptr, ui_font_args, &ui_exception);
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
        LT_LOGI("Dynamic CJK UI font creation family=sans-serif size=%d uiFont=%p exception=%p",
                ui_font_size, g_ui_font, ui_exception);
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

bool invoke_bool_getter(const void *method_info, void *instance, bool *value) {
    if (method_info == nullptr || instance == nullptr || value == nullptr ||
        g_runtime_invoke == nullptr || g_object_unbox == nullptr) {
        return false;
    }
    void *exception = nullptr;
    void *boxed = g_runtime_invoke(method_info, instance, nullptr, &exception);
    void *unboxed = exception == nullptr && boxed != nullptr ? g_object_unbox(boxed) : nullptr;
    if (unboxed == nullptr) return false;
    uint8_t raw = 0;
    memcpy(&raw, unboxed, sizeof(raw));
    *value = raw != 0;
    return true;
}

void invoke_bool_setter(const void *method_info, void *instance, bool value) {
    if (method_info == nullptr || instance == nullptr || g_runtime_invoke == nullptr) return;
    uint8_t raw = value ? 1 : 0;
    void *args[] = {&raw};
    void *exception = nullptr;
    g_runtime_invoke(method_info, instance, args, &exception);
}

bool invoke_int_getter(const void *method_info, void *instance, int32_t *value) {
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

void invoke_int_setter(const void *method_info, void *instance, int32_t value) {
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

struct ComponentRect {
    float width = 0.0f;
    float height = 0.0f;
    bool valid = false;
};

ComponentRect read_component_rect(void *instance, const void *get_rect_transform_info) {
    ComponentRect result;
    if (instance == nullptr || get_rect_transform_info == nullptr ||
        g_rect_transform_get_rect_info == nullptr || g_runtime_invoke == nullptr ||
        g_object_unbox == nullptr) {
        return result;
    }
    void *rect_transform = invoke_object_getter(get_rect_transform_info, instance);
    if (rect_transform == nullptr) return result;
    void *exception = nullptr;
    void *boxed_rect = g_runtime_invoke(
            g_rect_transform_get_rect_info, rect_transform, nullptr, &exception);
    void *unboxed_rect = exception == nullptr && boxed_rect != nullptr ?
            g_object_unbox(boxed_rect) : nullptr;
    if (unboxed_rect == nullptr) return result;
    // UnityEngine.Rect 的顺序为 x、y、width、height；这里只读取排版需要的后两个值。
    float rect_values[4] = {};
    memcpy(rect_values, unboxed_rect, sizeof(rect_values));
    result.width = rect_values[2];
    result.height = rect_values[3];
    result.valid = result.width > 0.0f && result.height > 0.0f &&
            result.width < 100000.0f && result.height < 100000.0f;
    return result;
}

std::string read_unity_object_name(void *instance) {
    std::string name;
    void *managed_name = invoke_object_getter(g_object_get_name_info, instance);
    if (managed_name == nullptr || g_string_length == nullptr || g_string_chars == nullptr) {
        return name;
    }
    int32_t length = g_string_length(managed_name);
    if (length < 0 || length > 1024 ||
        !utf16_to_utf8(g_string_chars(managed_name), length, &name)) {
        name.clear();
    }
    return name;
}

std::string read_unity_parent_name(void *component) {
    void *transform = invoke_object_getter(g_component_get_transform_info, component);
    void *parent = invoke_object_getter(g_transform_get_parent_info, transform);
    return read_unity_object_name(parent);
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
    candidate.has_font_size = invoke_float_getter(
            g_tmp_get_font_size_info, instance, &candidate.font_size);
    candidate.has_font_size_min = invoke_float_getter(
            g_tmp_get_font_size_min_info, instance, &candidate.font_size_min);
    candidate.has_font_size_max = invoke_float_getter(
            g_tmp_get_font_size_max_info, instance, &candidate.font_size_max);
    candidate.has_auto_sizing = invoke_bool_getter(
            g_tmp_get_auto_sizing_info, instance, &candidate.auto_sizing);
    candidate.has_word_wrapping = invoke_bool_getter(
            g_tmp_get_word_wrapping_info, instance, &candidate.word_wrapping);
    candidate.has_overflow_mode = invoke_int_getter(
            g_tmp_get_overflow_mode_info, instance, &candidate.overflow_mode);
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

void *translated_tmp_material(void *instance, void *requested_material) {
    TmpComponentState state;
    if (instance == nullptr || !find_tmp_component_state(instance, &state) ||
        g_tmp_get_font == nullptr ||
        g_tmp_get_font(instance, g_tmp_get_font_info) != g_tmp_font_asset) {
        return requested_material;
    }
    // 游戏会在页签选择、E.G.O 等级切换和角色刷新时重新写入原日文字体材质。
    // 材质仍指向旧图集时，即使文本和中文字体正确，也会随机显示成黑块、黄块或碎字。
    void *font_material = invoke_object_getter(
            g_tmp_font_get_material_info, g_tmp_font_asset);
    return font_material == nullptr ? requested_material : font_material;
}

void replacement_tmp_set_shared_material(void *instance, void *material, const void *method) {
    g_original_tmp_set_shared_material(
            instance, translated_tmp_material(instance, material), method);
}

void replacement_tmp_set_material(void *instance, void *material, const void *method) {
    g_original_tmp_set_material(
            instance, translated_tmp_material(instance, material), method);
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
    // 组件会被列表复用；必须恢复字体、材质与全部排版约束，避免中文缩放泄漏到日文页面。
    if (g_tmp_set_font != nullptr) g_tmp_set_font(instance, state.font, g_tmp_set_font_info);
    if (state.shared_material != nullptr) {
        invoke_object_setter(g_tmp_set_shared_material_info, instance, state.shared_material);
    }
    if (state.has_line_spacing) {
        invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
    }
    if (state.has_auto_sizing) {
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, state.auto_sizing);
    }
    if (state.has_word_wrapping) {
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, state.word_wrapping);
    }
    if (state.has_overflow_mode) {
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance, state.overflow_mode);
    }
    if (state.has_font_size_min) {
        invoke_float_setter(g_tmp_set_font_size_min_info, instance, state.font_size_min);
    }
    if (state.has_font_size_max) {
        invoke_float_setter(g_tmp_set_font_size_max_info, instance, state.font_size_max);
    }
    if (state.has_font_size) {
        invoke_float_setter(g_tmp_set_font_size_info, instance, state.font_size);
    }
}

UiComponentState capture_ui_component_state(void *instance) {
    UiComponentState candidate;
    candidate.font = invoke_object_getter(g_ui_get_font_info, instance);
    candidate.has_line_spacing = invoke_float_getter(
            g_ui_get_line_spacing_info, instance, &candidate.line_spacing);
    candidate.has_font_size = invoke_int_getter(
            g_ui_get_font_size_info, instance, &candidate.font_size);
    candidate.has_resize_min_size = invoke_int_getter(
            g_ui_get_resize_min_size_info, instance, &candidate.resize_min_size);
    candidate.has_resize_max_size = invoke_int_getter(
            g_ui_get_resize_max_size_info, instance, &candidate.resize_max_size);
    candidate.has_resize_best_fit = invoke_bool_getter(
            g_ui_get_resize_best_fit_info, instance, &candidate.resize_best_fit);
    candidate.has_horizontal_overflow = invoke_int_getter(
            g_ui_get_horizontal_overflow_info, instance, &candidate.horizontal_overflow);
    candidate.has_vertical_overflow = invoke_int_getter(
            g_ui_get_vertical_overflow_info, instance, &candidate.vertical_overflow);
    pthread_mutex_lock(&g_font_state_lock);
    auto inserted = g_original_ui_states.emplace(instance, candidate);
    UiComponentState state = inserted.first->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return state;
}

bool find_ui_component_state(void *instance, UiComponentState *state) {
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_ui_states.find(instance);
    bool found = saved != g_original_ui_states.end();
    if (found && state != nullptr) *state = saved->second;
    pthread_mutex_unlock(&g_font_state_lock);
    return found;
}

void restore_ui_component_state(void *instance) {
    UiComponentState state;
    bool restore = false;
    pthread_mutex_lock(&g_font_state_lock);
    auto saved = g_original_ui_states.find(instance);
    if (saved != g_original_ui_states.end()) {
        state = saved->second;
        g_original_ui_states.erase(saved);
        restore = true;
    }
    pthread_mutex_unlock(&g_font_state_lock);
    if (!restore) return;
    // 列表和页签会复用旧 UI.Text；离开中文文本时恢复原设置，避免影响未汉化资源。
    if (state.font != nullptr) invoke_object_setter(g_ui_set_font_info, instance, state.font);
    if (state.has_line_spacing) {
        invoke_float_setter(g_ui_set_line_spacing_info, instance, state.line_spacing);
    }
    if (state.has_resize_best_fit) {
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, state.resize_best_fit);
    }
    if (state.has_resize_min_size) {
        invoke_int_setter(g_ui_set_resize_min_size_info, instance, state.resize_min_size);
    }
    if (state.has_resize_max_size) {
        invoke_int_setter(g_ui_set_resize_max_size_info, instance, state.resize_max_size);
    }
    if (state.has_horizontal_overflow) {
        invoke_int_setter(
                g_ui_set_horizontal_overflow_info, instance, state.horizontal_overflow);
    }
    if (state.has_vertical_overflow) {
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, state.vertical_overflow);
    }
    if (state.has_font_size) {
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
    }
}

size_t count_visible_codepoints(const std::string &text) {
    size_t count = 0;
    for (size_t offset = 0; offset < text.size();) {
        // 富文本标签不占显示宽度，不能让颜色、样式参数把短标签误判成长正文。
        if (text[offset] == '<') {
            size_t tag_end = text.find('>', offset + 1);
            if (tag_end != std::string::npos) {
                offset = tag_end + 1;
                continue;
            }
        }
        unsigned char byte = static_cast<unsigned char>(text[offset++]);
        if ((byte & 0xc0) != 0x80) ++count;
    }
    return count;
}

void apply_translated_tmp_style(void *instance, void *managed_text,
                                const TmpComponentState &state) {
    if (managed_text == nullptr || g_string_length == nullptr ||
        g_string_chars == nullptr) {
        return;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return;
    }

    if (!state.has_font_size || state.font_size <= 0.0f) return;

    size_t visible_length = count_visible_codepoints(text);
    bool has_explicit_break = text.find('\n') != std::string::npos ||
            text.find('\r') != std::string::npos;
    ComponentRect rect = read_component_rect(instance, g_tmp_get_rect_transform_info);
    bool short_text = visible_length <= 48;
    std::string component_name;
    std::string parent_name;
    bool needs_component_identity = has_explicit_break ||
            (rect.width > 0.0f && rect.width < 140.0f && rect.height <= 0.0f);
    if (needs_component_identity) {
        component_name = read_unity_object_name(instance);
        parent_name = read_unity_parent_name(instance);
    }
    // 编队页、人格排列页与人格选择页会复用同名文本组件，但父节点名称并不一致。
    // 使用精确组件名覆盖三类页面，避免“公主”等包内已有换行只在部分页面生效。
    bool personality_group_name = component_name == "[Text]GroupName";
    bool formation_deck_label = component_name == "[Text]Deck" &&
            parent_name == "[Image]Mask";
    bool single_line = short_text && !has_explicit_break;

    if (single_line) {
        // 页签、技能名和横幅必须保持单行。之前只启用自动字号却没有关闭换行，
        // TMP 会先把中文拆成数行，再被一行高的遮罩裁成黄块或黑块。
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, false);
        // Ellipsis 与自动字号配合：先在可读字号范围内缩小，仍放不下时才显示省略号。
        // 战斗技能名因此不会换出第二行，也不会为了显示全名缩成难以辨认的小字。
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance,
                          rect.valid ? 1 : 0);  // 有有效宽度时 Ellipsis，否则 Overflow。
        float scale = visible_length <= 8 ? 1.0f :
                (visible_length <= 20 ? 0.96f : 0.90f);
        // 左侧编队槽原始字号为 30，在手机上辨识度不足；该控件高度由遮罩动态提供，
        // 因此固定为用户指定的 35，避免自动字号再次把长编队名缩小。
        float maximum = formation_deck_label ? 35.0f : state.font_size * scale;
        float minimum = formation_deck_label ? 35.0f :
                std::min(maximum, std::max(9.0f, state.font_size * 0.55f));
        invoke_float_setter(g_tmp_set_font_size_min_info, instance, minimum);
        invoke_float_setter(g_tmp_set_font_size_max_info, instance, maximum);
        invoke_float_setter(g_tmp_set_font_size_info, instance, maximum);
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, true);
        if (state.has_line_spacing) {
            invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
        }
    } else if (short_text) {
        // 汉化包明确保留换行时，使用原字号并扩大上下行距离；不再通过缩小字号
        // 掩盖重叠。关闭自动换行后只尊重文本已有的换行符，避免额外拆出第三行。
        invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, false);
        invoke_int_setter(g_tmp_set_overflow_mode_info, instance, 0);  // Overflow.
        invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, false);
        invoke_float_setter(g_tmp_set_font_size_info, instance, state.font_size);
        // 人格卡名由富文本 line-height 控制，此处归零额外行距，避免两套机制叠加；
        // 其他多行控件仍使用较温和的 24% 间距。
        float safe_spacing = personality_group_name ? 0.0f :
                std::max(state.font_size * 0.24f,
                         state.has_line_spacing ? state.line_spacing : 0.0f);
        invoke_float_setter(g_tmp_set_line_spacing_info, instance, safe_spacing);
    } else {
        // 长说明恢复页面原有换行与溢出策略，字号不再统一缩小 8%；正文优先保持可读。
        if (state.has_word_wrapping) {
            invoke_bool_setter(g_tmp_set_word_wrapping_info, instance, state.word_wrapping);
        }
        if (state.has_overflow_mode) {
            invoke_int_setter(g_tmp_set_overflow_mode_info, instance, state.overflow_mode);
        }
        if (state.has_line_spacing) {
            invoke_float_setter(g_tmp_set_line_spacing_info, instance, state.line_spacing);
        }
        if (state.has_auto_sizing) {
            invoke_bool_setter(g_tmp_set_auto_sizing_info, instance, state.auto_sizing);
        }
        if (state.has_font_size_min) {
            invoke_float_setter(g_tmp_set_font_size_min_info, instance, state.font_size_min);
        }
        if (state.has_font_size_max) {
            invoke_float_setter(g_tmp_set_font_size_max_info, instance, state.font_size_max);
        }
        invoke_float_setter(g_tmp_set_font_size_info, instance, state.font_size * 0.98f);
    }

    uint64_t decisions = g_layout_decisions.fetch_add(1, std::memory_order_relaxed) + 1;
    if (decisions <= 20 || decisions % 500 == 0) {
        // 诊断只记录几何和分类，不输出正文内容；用户回传日志即可判断控件为何选了单/双行。
        LT_LOGI("TMP layout decision=%llu chars=%zu rect=%.1fx%.1f font=%.1f single=%d short=%d",
                static_cast<unsigned long long>(decisions), visible_length,
                rect.width, rect.height, state.font_size, single_line, short_text);
    }
}

void *apply_tmp_component_layout_markup(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        g_string_length == nullptr || g_string_chars == nullptr) {
        return managed_text;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 4096 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text) ||
        text.find("<line-height=") != std::string::npos) {
        return managed_text;
    }
    std::string component_name = read_unity_object_name(instance);
    if (component_name != "[Text]GroupName") {
        return managed_text;
    }
    // 某些页面会把包内换行压成空格或直接去掉。通过当前汉化包自动生成的兼容索引
    // 恢复原始分行，确保任意人格标题都遵循包内排版，而不是只修复已知个例。
    auto package_layout = g_package_line_breaks.find(text);
    if (package_layout != g_package_line_breaks.end()) {
        text = package_layout->second;
    }
    if (text.find('\n') == std::string::npos) {
        return managed_text;
    }
    // 人格名称控件会忽略运行时 lineSpacing，却会在生成网格时遵守 TMP 富文本行高。
    // 只移动空格处的换行，不增删或改写汉化包正文，并统一使用用户指定的 150% 行高。
    size_t line_count = 0;
    std::string reflowed = reflow_personality_title(text, &line_count);
    // 用户要求所有人格卡底栏保持一致，二行与三行标题统一采用 150% 行高。
    std::string formatted = apply_uniform_line_height(reflowed, 150);
    void *replacement = new_managed_string(formatted);
    if (replacement == nullptr) return managed_text;
    pthread_mutex_lock(&g_font_state_lock);
    g_translated_managed_texts.insert(formatted);
    pthread_mutex_unlock(&g_font_state_lock);
    return replacement;
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
            replacement = apply_tmp_component_layout_markup(instance, replacement);
            bool translated = is_translated_managed_string(replacement);
            if (translated && ensure_tmp_font() && g_tmp_get_font != nullptr &&
                g_tmp_set_font != nullptr) {
                void *current_font = g_tmp_get_font(instance, g_tmp_get_font_info);
                if (current_font != g_tmp_font_asset) {
                    capture_tmp_component_state(instance, current_font);
                    g_tmp_set_font(instance, g_tmp_font_asset, g_tmp_set_font_info);
                    // TMP 组件可能保留旧字体的共享材质；字体图集与材质不匹配时会把
                    // 正确字形采样成碎片或白块，因此在字体切换后显式绑定中文字体材质。
                    void *font_material = invoke_object_getter(
                            g_tmp_font_get_material_info, g_tmp_font_asset);
                    if (font_material != nullptr) {
                        invoke_object_setter(
                                g_tmp_set_shared_material_info, instance, font_material);
                    }
                    uint64_t switches = g_tmp_primary_font_switches.fetch_add(
                            1, std::memory_order_relaxed) + 1;
                    if (switches == 1 || switches % 500 == 0) {
                        // 周期日志会保留到较晚导出的诊断包，便于确认主字体切换确实发生。
                        LT_LOGI("TMP Chinese primary font switches=%llu font=%p",
                                static_cast<unsigned long long>(switches), g_tmp_font_asset);
                    }
                }
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

void finalize_tmp_text(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        !is_translated_managed_string(managed_text)) {
        return;
    }
    TmpComponentState state;
    if (find_tmp_component_state(instance, &state)) {
        // 必须在原始 set_text/SetText 完成后再应用字号和换行；否则游戏 setter 会用
        // 旧文本重新计算布局，导致我们设置的自动缩放没有真正处理新中文。
        apply_translated_tmp_style(instance, managed_text, state);
    }
}

void apply_translated_ui_style(void *instance, void *managed_text,
                               const UiComponentState &state) {
    if (managed_text == nullptr || !state.has_font_size || state.font_size <= 0 ||
        g_string_length == nullptr || g_string_chars == nullptr) {
        return;
    }
    int32_t length = g_string_length(managed_text);
    std::string text;
    if (length < 0 || length > 1024 * 1024 ||
        !utf16_to_utf8(g_string_chars(managed_text), length, &text)) {
        return;
    }
    size_t visible_length = count_visible_codepoints(text);
    bool has_explicit_break = text.find('\n') != std::string::npos ||
            text.find('\r') != std::string::npos;
    bool short_text = visible_length <= 48;
    ComponentRect rect = read_component_rect(instance, g_ui_get_rect_transform_info);
    bool single_line = short_text && !has_explicit_break;

    if (single_line) {
        // 旧 UI.Text 没有 TMP 的单行自动缩放。根据矩形宽度直接计算安全字号，
        // 然后关闭换行和裁切，保证横幅与页签不会只剩零碎笔画。
        float scale = 1.0f;
        if (rect.valid && visible_length > 0) {
            float estimated_width = static_cast<float>(visible_length * state.font_size);
            scale = std::min(1.0f, rect.width / std::max(1.0f, estimated_width));
        }
        int32_t safe_size = std::max(7, static_cast<int32_t>(
                static_cast<float>(state.font_size) * scale * 0.96f));
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, false);
        invoke_int_setter(g_ui_set_font_size_info, instance, safe_size);
        invoke_int_setter(g_ui_set_horizontal_overflow_info, instance, 1);  // Overflow.
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, 1);    // Overflow.
        if (state.has_line_spacing) {
            invoke_float_setter(g_ui_set_line_spacing_info, instance,
                                std::max(1.0f, state.line_spacing));
        }
    } else if (short_text) {
        // 汉化包明确含换行时保留原字号，并用 Unity UI 的行高倍率拉开上下距离。
        invoke_bool_setter(g_ui_set_resize_best_fit_info, instance, false);
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
        invoke_int_setter(g_ui_set_horizontal_overflow_info, instance, 0);  // Wrap.
        invoke_int_setter(g_ui_set_vertical_overflow_info, instance, 1);    // Overflow.
        invoke_float_setter(g_ui_set_line_spacing_info, instance,
                            state.has_line_spacing ? std::max(1.24f, state.line_spacing) : 1.24f);
    } else {
        // 正文保留游戏原排版，避免为了塞进一屏而缩成难以阅读的小字。
        if (state.has_resize_best_fit) {
            invoke_bool_setter(
                    g_ui_set_resize_best_fit_info, instance, state.resize_best_fit);
        }
        if (state.has_resize_min_size) {
            invoke_int_setter(
                    g_ui_set_resize_min_size_info, instance, state.resize_min_size);
        }
        if (state.has_resize_max_size) {
            invoke_int_setter(
                    g_ui_set_resize_max_size_info, instance, state.resize_max_size);
        }
        if (state.has_horizontal_overflow) {
            invoke_int_setter(g_ui_set_horizontal_overflow_info, instance,
                              state.horizontal_overflow);
        }
        if (state.has_vertical_overflow) {
            invoke_int_setter(
                    g_ui_set_vertical_overflow_info, instance, state.vertical_overflow);
        }
        if (state.has_line_spacing) {
            invoke_float_setter(g_ui_set_line_spacing_info, instance, state.line_spacing);
        }
        invoke_int_setter(g_ui_set_font_size_info, instance, state.font_size);
    }
}

void *prepare_ui_text(void *instance, void *managed_text) {
    void *replacement = translate_managed_string(managed_text);
    bool translated = replacement != nullptr && is_translated_managed_string(replacement);
    if (!translated) {
        restore_ui_component_state(instance);
        return replacement;
    }
    ensure_tmp_font();
    if (instance == nullptr || g_ui_font == nullptr) return replacement;
    void *current_font = invoke_object_getter(g_ui_get_font_info, instance);
    if (current_font != g_ui_font) {
        capture_ui_component_state(instance);
        invoke_object_setter(g_ui_set_font_info, instance, g_ui_font);
        uint64_t switches = g_ui_primary_font_switches.fetch_add(
                1, std::memory_order_relaxed) + 1;
        if (switches == 1 || switches % 500 == 0) {
            LT_LOGI("UI.Text Chinese primary font switches=%llu font=%p",
                    static_cast<unsigned long long>(switches), g_ui_font);
        }
    }
    return replacement;
}

void finalize_ui_text(void *instance, void *managed_text) {
    if (instance == nullptr || managed_text == nullptr ||
        !is_translated_managed_string(managed_text)) {
        return;
    }
    UiComponentState state;
    if (find_ui_component_state(instance, &state)) {
        apply_translated_ui_style(instance, managed_text, state);
    }
}

void replacement_tmp_set_text(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text(instance, replacement, method);
    finalize_tmp_text(instance, replacement);
}

void replacement_tmp_set_text_one(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text_one(instance, replacement, method);
    finalize_tmp_text(instance, replacement);
}

void replacement_ui_set_text(void *instance, void *managed_text, const void *method) {
    void *replacement = prepare_ui_text(instance, managed_text);
    g_original_ui_set_text(instance, replacement, method);
    finalize_ui_text(instance, replacement);
}

void replacement_tmp_set_text_string(void *instance, void *managed_text, bool sync_input,
                                     const void *method) {
    void *replacement = prepare_tmp_text(instance, managed_text);
    g_original_tmp_set_text_string(instance, replacement, sync_input, method);
    finalize_tmp_text(instance, replacement);
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

void rebuild_package_line_break_index(
        const std::unordered_map<std::string, std::string> &translations) {
    g_package_line_breaks.clear();
    std::unordered_set<std::string> ambiguous;
    const auto register_variant = [&ambiguous](const std::string &variant,
                                               const std::string &translation) {
        if (variant == translation || ambiguous.find(variant) != ambiguous.end()) return;
        auto existing = g_package_line_breaks.find(variant);
        if (existing == g_package_line_breaks.end()) {
            g_package_line_breaks.emplace(variant, translation);
        } else if (existing->second != translation) {
            // 展平形式有歧义时宁可保留游戏文本，也不能恢复成另一个人格的换行结构。
            g_package_line_breaks.erase(existing);
            ambiguous.insert(variant);
        }
    };
    for (const auto &entry : translations) {
        const std::string &translation = entry.second;
        if (translation.find('\n') == std::string::npos) continue;
        std::string spaced = translation;
        std::replace(spaced.begin(), spaced.end(), '\n', ' ');
        register_variant(spaced, translation);
        std::string compact = translation;
        compact.erase(std::remove(compact.begin(), compact.end(), '\n'), compact.end());
        register_variant(compact, translation);
    }
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
    rebuild_package_line_break_index(g_index);
    rebuild_term_trie();
    LT_LOGI("Loaded Japanese full-text translation index entries=%zu terms=%zu lineBreaks=%zu path=%s",
            g_index.size(), g_terms.size(), g_package_line_breaks.size(), path);
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
    void *unity_object_class = nullptr;
    void *component_class = nullptr;
    void *transform_class = nullptr;
    void *rect_transform_class = nullptr;
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
        if (unity_object_class == nullptr) {
            unity_object_class = class_from_name(image, "UnityEngine", "Object");
        }
        if (component_class == nullptr) {
            component_class = class_from_name(image, "UnityEngine", "Component");
        }
        if (transform_class == nullptr) {
            transform_class = class_from_name(image, "UnityEngine", "Transform");
        }
        if (rect_transform_class == nullptr) {
            rect_transform_class = class_from_name(image, "UnityEngine", "RectTransform");
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
    const void *tmp_set_font_material_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontMaterial", 1);
    const void *tmp_font_get_material_method = tmp_font_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_font_class, "get_material", 0);
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
    const void *tmp_set_font_size_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSize", 1);
    const void *tmp_get_font_size_min_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSizeMin", 0);
    const void *tmp_set_font_size_min_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSizeMin", 1);
    const void *tmp_get_font_size_max_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_fontSizeMax", 0);
    const void *tmp_set_font_size_max_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_fontSizeMax", 1);
    const void *tmp_get_auto_sizing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_enableAutoSizing", 0);
    const void *tmp_set_auto_sizing_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_enableAutoSizing", 1);
    const void *tmp_get_word_wrapping_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_enableWordWrapping", 0);
    const void *tmp_set_word_wrapping_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_enableWordWrapping", 1);
    const void *tmp_get_overflow_mode_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_overflowMode", 0);
    const void *tmp_set_overflow_mode_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "set_overflowMode", 1);
    const void *tmp_get_rect_transform_method = tmp_text_class == nullptr ? nullptr :
            class_get_method_from_name(tmp_text_class, "get_rectTransform", 0);
    const void *ui_get_font_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_font", 0);
    const void *ui_set_font_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_font", 1);
    const void *ui_get_line_spacing_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_lineSpacing", 0);
    const void *ui_set_line_spacing_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_lineSpacing", 1);
    const void *ui_get_font_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_fontSize", 0);
    const void *ui_set_font_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_fontSize", 1);
    const void *ui_get_resize_min_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextMinSize", 0);
    const void *ui_set_resize_min_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextMinSize", 1);
    const void *ui_get_resize_max_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextMaxSize", 0);
    const void *ui_set_resize_max_size_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextMaxSize", 1);
    const void *ui_get_resize_best_fit_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_resizeTextForBestFit", 0);
    const void *ui_set_resize_best_fit_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_resizeTextForBestFit", 1);
    const void *ui_get_horizontal_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_horizontalOverflow", 0);
    const void *ui_set_horizontal_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_horizontalOverflow", 1);
    const void *ui_get_vertical_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_verticalOverflow", 0);
    const void *ui_set_vertical_overflow_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "set_verticalOverflow", 1);
    const void *ui_get_rect_transform_method = ui_text_class == nullptr ? nullptr :
            class_get_method_from_name(ui_text_class, "get_rectTransform", 0);
    const void *rect_transform_get_rect_method = rect_transform_class == nullptr ? nullptr :
            class_get_method_from_name(rect_transform_class, "get_rect", 0);
    const void *object_get_name_method = unity_object_class == nullptr ? nullptr :
            class_get_method_from_name(unity_object_class, "get_name", 0);
    const void *component_get_transform_method = component_class == nullptr ? nullptr :
            class_get_method_from_name(component_class, "get_transform", 0);
    const void *transform_get_parent_method = transform_class == nullptr ? nullptr :
            class_get_method_from_name(transform_class, "get_parent", 0);
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
    LT_LOGI("TMP style methods material=%p fontAssetMaterial=%p fontMaterial=%p/%p shared=%p/%p spacing=%p/%p"
            " fontSize=%p/%p min=%p/%p max=%p/%p auto=%p/%p wrap=%p/%p overflow=%p/%p"
            " rect=%p/%p outline=%p/%p/%p/%p",
            material_class, tmp_font_get_material_method,
            tmp_get_font_material_method, tmp_set_font_material_method,
            tmp_get_shared_material_method,
            tmp_set_shared_material_method, tmp_get_line_spacing_method,
            tmp_set_line_spacing_method, tmp_get_font_size_method, tmp_set_font_size_method,
            tmp_get_font_size_min_method, tmp_set_font_size_min_method,
            tmp_get_font_size_max_method, tmp_set_font_size_max_method,
            tmp_get_auto_sizing_method, tmp_set_auto_sizing_method,
            tmp_get_word_wrapping_method, tmp_set_word_wrapping_method,
            tmp_get_overflow_mode_method, tmp_set_overflow_mode_method,
            tmp_get_rect_transform_method, rect_transform_get_rect_method,
            material_get_float_method, material_set_float_method,
            material_get_color_method, material_set_color_method);
    LT_LOGI("UI.Text style methods font=%p/%p spacing=%p/%p fontSize=%p/%p min=%p/%p"
            " max=%p/%p bestFit=%p/%p horizontal=%p/%p vertical=%p/%p rect=%p/%p",
            ui_get_font_method, ui_set_font_method,
            ui_get_line_spacing_method, ui_set_line_spacing_method,
            ui_get_font_size_method, ui_set_font_size_method,
            ui_get_resize_min_size_method, ui_set_resize_min_size_method,
            ui_get_resize_max_size_method, ui_set_resize_max_size_method,
            ui_get_resize_best_fit_method, ui_set_resize_best_fit_method,
            ui_get_horizontal_overflow_method, ui_set_horizontal_overflow_method,
            ui_get_vertical_overflow_method, ui_set_vertical_overflow_method,
            ui_get_rect_transform_method, rect_transform_get_rect_method);
    LT_LOGI("Unity hierarchy methods name=%p transform=%p parent=%p",
            object_get_name_method, component_get_transform_method,
            transform_get_parent_method);
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
        g_tmp_font_get_material_info = tmp_font_get_material_method;
        g_tmp_get_font_material_info = tmp_get_font_material_method;
        g_tmp_get_shared_material_info = tmp_get_shared_material_method;
        g_tmp_set_shared_material_info = tmp_set_shared_material_method;
        g_tmp_get_line_spacing_info = tmp_get_line_spacing_method;
        g_tmp_set_line_spacing_info = tmp_set_line_spacing_method;
        g_tmp_get_font_size_info = tmp_get_font_size_method;
        g_tmp_set_font_size_info = tmp_set_font_size_method;
        g_tmp_get_font_size_min_info = tmp_get_font_size_min_method;
        g_tmp_set_font_size_min_info = tmp_set_font_size_min_method;
        g_tmp_get_font_size_max_info = tmp_get_font_size_max_method;
        g_tmp_set_font_size_max_info = tmp_set_font_size_max_method;
        g_tmp_get_auto_sizing_info = tmp_get_auto_sizing_method;
        g_tmp_set_auto_sizing_info = tmp_set_auto_sizing_method;
        g_tmp_get_word_wrapping_info = tmp_get_word_wrapping_method;
        g_tmp_set_word_wrapping_info = tmp_set_word_wrapping_method;
        g_tmp_get_overflow_mode_info = tmp_get_overflow_mode_method;
        g_tmp_set_overflow_mode_info = tmp_set_overflow_mode_method;
        g_tmp_get_rect_transform_info = tmp_get_rect_transform_method;
        g_ui_get_font_info = ui_get_font_method;
        g_ui_set_font_info = ui_set_font_method;
        g_ui_get_line_spacing_info = ui_get_line_spacing_method;
        g_ui_set_line_spacing_info = ui_set_line_spacing_method;
        g_ui_get_font_size_info = ui_get_font_size_method;
        g_ui_set_font_size_info = ui_set_font_size_method;
        g_ui_get_resize_min_size_info = ui_get_resize_min_size_method;
        g_ui_set_resize_min_size_info = ui_set_resize_min_size_method;
        g_ui_get_resize_max_size_info = ui_get_resize_max_size_method;
        g_ui_set_resize_max_size_info = ui_set_resize_max_size_method;
        g_ui_get_resize_best_fit_info = ui_get_resize_best_fit_method;
        g_ui_set_resize_best_fit_info = ui_set_resize_best_fit_method;
        g_ui_get_horizontal_overflow_info = ui_get_horizontal_overflow_method;
        g_ui_set_horizontal_overflow_info = ui_set_horizontal_overflow_method;
        g_ui_get_vertical_overflow_info = ui_get_vertical_overflow_method;
        g_ui_set_vertical_overflow_info = ui_set_vertical_overflow_method;
        g_ui_get_rect_transform_info = ui_get_rect_transform_method;
        g_rect_transform_get_rect_info = rect_transform_get_rect_method;
        g_object_get_name_info = object_get_name_method;
        g_component_get_transform_info = component_get_transform_method;
        g_transform_get_parent_info = transform_get_parent_method;
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
        void *set_shared_material_pointer = nullptr;
        if (tmp_set_shared_material_method != nullptr) {
            memcpy(&set_shared_material_pointer, tmp_set_shared_material_method,
                   sizeof(set_shared_material_pointer));
        }
        if (set_shared_material_pointer != nullptr &&
            has_inline_hook_space(tmp_text_class, set_shared_material_pointer)) {
            MSHookFunction(set_shared_material_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_shared_material),
                           reinterpret_cast<void **>(&g_original_tmp_set_shared_material));
        } else if (set_shared_material_pointer != nullptr) {
            LT_LOGW("Skip unsafe adjacent TMP shared material setter=%p",
                    set_shared_material_pointer);
        }
        void *set_material_pointer = nullptr;
        if (tmp_set_font_material_method != nullptr) {
            memcpy(&set_material_pointer, tmp_set_font_material_method,
                   sizeof(set_material_pointer));
        }
        if (set_material_pointer != nullptr &&
            set_material_pointer != set_shared_material_pointer &&
            has_inline_hook_space(tmp_text_class, set_material_pointer)) {
            MSHookFunction(set_material_pointer,
                           reinterpret_cast<void *>(replacement_tmp_set_material),
                           reinterpret_cast<void **>(&g_original_tmp_set_material));
        } else if (set_material_pointer != nullptr &&
                   set_material_pointer != set_shared_material_pointer) {
            LT_LOGW("Skip unsafe adjacent TMP material setter=%p", set_material_pointer);
        }
        LT_LOGI("TMP material hooks shared=%p/%p instance=%p/%p",
                set_shared_material_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_shared_material),
                set_material_pointer,
                reinterpret_cast<void *>(g_original_tmp_set_material));
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
    g_package_line_breaks.clear();
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
