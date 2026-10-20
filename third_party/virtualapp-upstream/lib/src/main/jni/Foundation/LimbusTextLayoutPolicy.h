#pragma once

#include <cmath>
#include <string>

namespace limbus_text_layout {

// TMP's current fontSize can already be the result of a previous auto-size pass.
inline float configured_font_size(float rendered_size, bool auto_sizing,
                                  bool has_maximum, float maximum) {
    return auto_sizing && has_maximum && std::isfinite(maximum) && maximum > 0.0f
            ? maximum : rendered_size;
}

inline bool has_explicit_break(const std::string &text) {
    if (text.find_first_of("\r\n") != std::string::npos) return true;
    for (size_t start = text.find('<'); start != std::string::npos;
         start = text.find('<', start + 1)) {
        size_t end = text.find('>', start + 1);
        if (end == std::string::npos) break;
        std::string tag = text.substr(start + 1, end - start - 1);
        if (tag == "br" || tag == "br/" || tag == "br /" ||
            tag == "BR" || tag == "BR/" || tag == "BR /") return true;
        start = end;
    }
    return false;
}

inline bool is_single_line(bool explicit_break, bool rect_valid, float height,
                           float font_size, bool has_wrapping, bool wrapping) {
    if (explicit_break) return false;
    // Use the original control's layout, never a character-count threshold.
    return (has_wrapping && !wrapping) ||
            (rect_valid && font_size > 0.0f && height <= font_size * 1.8f);
}

inline float multiline_spacing(float original, float font_size,
                               bool explicit_line_height) {
    return explicit_line_height ? original : std::fmax(original, font_size * 0.24f);
}

}  // namespace limbus_text_layout
