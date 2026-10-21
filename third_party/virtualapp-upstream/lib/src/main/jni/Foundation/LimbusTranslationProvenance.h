#ifndef LIMBUS_TRANSLATION_PROVENANCE_H
#define LIMBUS_TRANSLATION_PROVENANCE_H

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_set>
#include <vector>

/** 保存已知译文模板在格式化结果中的来源保护区间，不按文本语言猜测来源。 */
namespace limbus_translation_guard {

/** 表示渲染文本中已由汉化层产生的连续字面片段，坐标使用 UTF-8 字节。 */
struct ProtectedSpan {
    /** 保护区间的起始字节下标，包含该位置。 */
    std::size_t begin;
    /** 保护区间的结束字节下标，不包含该位置。 */
    std::size_t end;
};

namespace detail {

/**
 * 判断模板中的当前字符是否可用于数字编号或对齐宽度。
 *
 * @param value 待检查的 ASCII 字符。
 * @return 字符为十进制数字时返回 true。
 */
inline bool is_format_digit(char value) {
    return value >= '0' && value <= '9';
}

/**
 * 跳过数字占位符中编号和对齐参数两侧的常见空白。
 *
 * @param value 模板原文。
 * @param position 待检查位置，并在返回时指向首个非空白字符。
 */
inline void skip_format_spaces(const std::string &value, std::size_t &position) {
    while (position < value.size() && (value[position] == ' ' || value[position] == '\t')) {
        ++position;
    }
}

/**
 * 识别数字编号的复合格式占位符，避免把转义花括号或普通字面内容当作参数。
 *
 * @param value 已知来源的中文模板。
 * @param begin 单个左花括号所在下标，调用方已排除双左花括号转义。
 * @return 合法占位符之后的位置；不符合编号、对齐和格式结构时返回 begin。
 */
inline std::size_t format_placeholder_end(const std::string &value, std::size_t begin) {
    std::size_t cursor = begin + 1;
    if (cursor >= value.size() || !is_format_digit(value[cursor])) return begin;
    while (cursor < value.size() && is_format_digit(value[cursor])) ++cursor;
    skip_format_spaces(value, cursor);
    if (cursor < value.size() && value[cursor] == ',') {
        ++cursor;
        skip_format_spaces(value, cursor);
        if (cursor < value.size() && (value[cursor] == '-' || value[cursor] == '+')) ++cursor;
        if (cursor >= value.size() || !is_format_digit(value[cursor])) return begin;
        while (cursor < value.size() && is_format_digit(value[cursor])) ++cursor;
        skip_format_spaces(value, cursor);
    }
    if (cursor < value.size() && value[cursor] == ':') {
        ++cursor;
        // 格式说明属于参数，不把其中的数字格式、日期文字等误当作模板字面。
        while (cursor < value.size() && value[cursor] != '}') {
            if (value[cursor] == '{') return begin;
            ++cursor;
        }
    }
    return cursor < value.size() && value[cursor] == '}' ? cursor + 1 : begin;
}

/**
 * 从 UTF-8 字符串中读取一个合法 Unicode 码点，损坏字节不会被当作可见字形。
 *
 * @param value 待读取的模板字面片段。
 * @param position 当前字节下标，成功后推进整个码点，失败后仅跳过当前字节。
 * @param codepoint 接收合法码点，失败时保持调用前的值。
 * @return 成功解码且不是过长编码、代理项或越界码点时返回 true。
 */
inline bool next_utf8_codepoint(
    const std::string &value, std::size_t &position, std::uint32_t &codepoint) {
    const auto first = static_cast<unsigned char>(value[position]);
    std::size_t length = 0;
    std::uint32_t decoded = 0;
    std::uint32_t minimum = 0;
    if (first < 0x80) {
        length = 1;
        decoded = first;
    } else if (first >= 0xc2 && first <= 0xdf) {
        length = 2;
        decoded = first & 0x1f;
        minimum = 0x80;
    } else if (first >= 0xe0 && first <= 0xef) {
        length = 3;
        decoded = first & 0x0f;
        minimum = 0x800;
    } else if (first >= 0xf0 && first <= 0xf4) {
        length = 4;
        decoded = first & 0x07;
        minimum = 0x10000;
    }
    if (length == 0 || length > value.size() - position) {
        ++position;
        return false;
    }
    for (std::size_t offset = 1; offset < length; ++offset) {
        const auto continuation = static_cast<unsigned char>(value[position + offset]);
        if ((continuation & 0xc0) != 0x80) {
            ++position;
            return false;
        }
        decoded = (decoded << 6) | (continuation & 0x3f);
    }
    if (decoded < minimum || decoded > 0x10ffff || (decoded >= 0xd800 && decoded <= 0xdfff)) {
        ++position;
        return false;
    }
    position += length;
    codepoint = decoded;
    return true;
}

/**
 * 确认字面片段包含可见的中日韩字形，防止仅有标点、数字或空白的模板保护大段参数。
 *
 * @param literal 去掉数字占位符并还原花括号转义后的字面片段。
 * @return 包含汉字、假名或韩文字母时返回 true；不根据该结果推断整段文本的来源。
 */
inline bool has_visible_translation_glyph(const std::string &literal) {
    std::size_t position = 0;
    while (position < literal.size()) {
        std::uint32_t cp = 0;
        if (!next_utf8_codepoint(literal, position, cp)) continue;
        // 使用明确字形区段，排除 CJK 标点区、全角数字和常见空白；不依赖 ICU。
        if ((cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff) ||
            (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x20000 && cp <= 0x2ebef) ||
            (cp >= 0x3041 && cp <= 0x3096) || (cp >= 0x309d && cp <= 0x309f) ||
            (cp >= 0x30a1 && cp <= 0x30fa) || (cp >= 0x30fd && cp <= 0x30ff) ||
            (cp >= 0x31f0 && cp <= 0x31ff) || (cp >= 0xff66 && cp <= 0xff9d) ||
            (cp >= 0x1100 && cp <= 0x11ff) || (cp >= 0x3131 && cp <= 0x318e) ||
            (cp >= 0xa960 && cp <= 0xa97c) || (cp >= 0xac00 && cp <= 0xd7a3) ||
            (cp >= 0xd7b0 && cp <= 0xd7fb)) return true;
    }
    return false;
}

/**
 * 将已知译文模板按数字占位符拆成有顺序的字面片段，保留空头尾与相邻参数之间的空片段。
 *
 * @param value 本次 formatter 已接管并产生的中文模板。
 * @return 去掉数字占位符并还原转义花括号的字面序列；只有一个片段时表示没有参数。
 */
inline std::vector<std::string> template_literal_chunks(const std::string &value) {
    std::vector<std::string> chunks;
    std::string literal;
    const auto flush = [&]() {
        // 空片段决定模板是否以参数开头/结尾，不能因为没有可保护字形就丢弃。
        chunks.push_back(literal);
        literal.clear();
    };
    for (std::size_t position = 0; position < value.size();) {
        const char current = value[position];
        if ((current == '{' || current == '}') && position + 1 < value.size() &&
            value[position + 1] == current) {
            // string.Format 会将双花括号还原为一个字面花括号，它们不是参数边界。
            literal += current;
            position += 2;
        } else if (current == '{') {
            const std::size_t end = format_placeholder_end(value, position);
            if (end != position) {
                flush();
                position = end;
            } else {
                literal += current;
                ++position;
            }
        } else {
            literal += current;
            ++position;
        }
    }
    flush();
    return chunks;
}

/**
 * 将完整含参模板与渲染文本对齐，只保护有唯一位置的模板字面，避免覆盖参数中的同文。
 *
 * @param text 已经替换参数的完整 formatter 输出。
 * @param chunks 保留空头尾的模板字面序列，相邻片段之间代表一个数字占位符。
 * @return 唯一对齐时返回含可见字形的字面区间；无法对齐或存在多种位置时返回空列表。
 */
inline std::vector<ProtectedSpan> uniquely_aligned_template_spans(
    const std::string &text, const std::vector<std::string> &chunks) {
    if (chunks.size() < 2) return {};
    std::vector<std::size_t> earliest(chunks.size(), std::string::npos);
    std::vector<std::size_t> latest(chunks.size(), std::string::npos);
    std::size_t cursor = 0;
    for (std::size_t index = 0; index < chunks.size(); ++index) {
        const auto &literal = chunks[index];
        if (literal.empty()) continue;
        std::size_t position = std::string::npos;
        if (index == 0) {
            // 开头不是参数时，模板字面必须从完整输出的起点开始。
            if (literal.size() <= text.size() && text.compare(0, literal.size(), literal) == 0) position = 0;
        } else if (index == chunks.size() - 1) {
            // 末尾不是参数时同样锚定终点；不能把参数中较早出现的同文当作模板后缀。
            if (literal.size() <= text.size()) {
                const std::size_t end_position = text.size() - literal.size();
                if (end_position >= cursor && text.compare(end_position, literal.size(), literal) == 0) {
                    position = end_position;
                }
            }
        } else {
            position = text.find(literal, cursor);
        }
        if (position == std::string::npos) return {};
        earliest[index] = position;
        cursor = position + literal.size();
    }

    std::size_t upper = text.size();
    for (std::size_t remaining = chunks.size(); remaining > 0; --remaining) {
        const std::size_t index = remaining - 1;
        const auto &literal = chunks[index];
        if (literal.empty()) continue;
        if (literal.size() > upper) return {};
        std::size_t position = std::string::npos;
        if (index == 0) {
            if (text.compare(0, literal.size(), literal) == 0) position = 0;
        } else if (index == chunks.size() - 1) {
            const std::size_t end_position = text.size() - literal.size();
            if (end_position + literal.size() <= upper &&
                text.compare(end_position, literal.size(), literal) == 0) position = end_position;
        } else {
            position = text.rfind(literal, upper - literal.size());
        }
        if (position == std::string::npos) return {};
        latest[index] = position;
        upper = position;
    }

    std::vector<ProtectedSpan> spans;
    for (std::size_t index = 0; index < chunks.size(); ++index) {
        const auto &literal = chunks[index];
        if (literal.empty()) continue;
        // 最早和最晚的合法字面位置相同才证明唯一；有歧义时放弃该模板，不枚举参数拆分。
        if (earliest[index] != latest[index]) return {};
        if (has_visible_translation_glyph(literal)) {
            spans.push_back({earliest[index], earliest[index] + literal.size()});
        }
    }
    return spans;
}

}  // namespace detail

/**
 * 保护本次 formatter 已产生的译文字面，只让后续文本匹配继续处理格式化参数值。
 *
 * 调用方必须传入有可靠来源凭据的模板，并把 text 限制在 1 MiB 内。本函数不检查文本
 * 是否“像中文”，也不输出正文；没有占位符的已知译文仍可作为完整字面保护。
 *
 * @param text 原 formatter 已替换数字占位符后的 UTF-8 显示文本。
 * @param templates 本次 formatter 接管产生的模板；最多检查前 256 个，超过 64 KiB 的单项跳过。
 * @return 唯一模板对齐和完整无参译文的 UTF-8 半开区间，按起点递增并合并重叠和相邻区间。
 */
inline std::vector<ProtectedSpan> protected_template_spans(
    const std::string &text, const std::vector<std::string> &templates) {
    if (text.empty() || templates.empty()) return {};
    // 使用字节覆盖差分合并，不为每次重复出现分配独立 span。模板总长至多 16 MiB，
    // 同一点的重叠覆盖计数小于该字节数，因此 int32_t 足够，且辅助空间仅与 text 长度相关。
    std::vector<std::int32_t> coverage(text.size() + 1, 0);
    std::unordered_set<std::string> visited_templates;
    bool found = false;
    const std::size_t template_count = std::min<std::size_t>(templates.size(), 256);
    for (std::size_t index = 0; index < template_count; ++index) {
        const auto &value = templates[index];
        // 不截断模板，避免切开 UTF-8 或占位符后制造不属于实际渲染结果的片段。
        if (value.size() > 64 * 1024 || !visited_templates.insert(value).second) continue;
        const auto chunks = detail::template_literal_chunks(value);
        if (chunks.size() == 1) {
            const auto &literal = chunks.front();
            if (literal.empty() || !detail::has_visible_translation_glyph(literal)) continue;
            // 无参模板可能是嵌套子 formatter 的完整返回值，只有完整连续片段才可作为来源依据。
            for (std::size_t position = text.find(literal); position != std::string::npos;
                 position = text.find(literal, position + 1)) {
                ++coverage[position];
                --coverage[position + literal.size()];
                found = true;
            }
        } else {
            for (const auto &span : detail::uniquely_aligned_template_spans(text, chunks)) {
                ++coverage[span.begin];
                --coverage[span.end];
                found = true;
            }
        }
    }
    if (!found) return {};
    std::vector<ProtectedSpan> spans;
    std::int32_t active = 0;
    std::size_t begin = 0;
    for (std::size_t position = 0; position <= text.size(); ++position) {
        const std::int32_t previous = active;
        active += coverage[position];
        if (previous == 0 && active > 0) begin = position;
        if (previous > 0 && active == 0) spans.push_back({begin, position});
    }
    return spans;
}

}  // namespace limbus_translation_guard

#endif  // LIMBUS_TRANSLATION_PROVENANCE_H
