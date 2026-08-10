#pragma once

#include <cstdint>
#include <string>

namespace transcript {

constexpr char16_t REPLACEMENT_CHARACTER = static_cast<char16_t>(0xFFFD);

inline std::u16string utf8_to_utf16_replacing_invalid(const std::string & input) {
    std::u16string output;
    output.reserve(input.size());

    const auto * bytes = reinterpret_cast<const uint8_t *>(input.data());
    size_t index = 0;
    while (index < input.size()) {
        const uint8_t first = bytes[index];
        uint32_t code_point = 0;
        size_t length = 0;
        uint32_t minimum = 0;

        if (first <= 0x7F) {
            code_point = first;
            length = 1;
        } else if (first >= 0xC2 && first <= 0xDF) {
            code_point = first & 0x1F;
            length = 2;
            minimum = 0x80;
        } else if (first >= 0xE0 && first <= 0xEF) {
            code_point = first & 0x0F;
            length = 3;
            minimum = 0x800;
        } else if (first >= 0xF0 && first <= 0xF4) {
            code_point = first & 0x07;
            length = 4;
            minimum = 0x10000;
        } else {
            output.push_back(REPLACEMENT_CHARACTER);
            ++index;
            continue;
        }

        bool valid = index + length <= input.size();
        for (size_t offset = 1; valid && offset < length; ++offset) {
            const uint8_t continuation = bytes[index + offset];
            if ((continuation & 0xC0) != 0x80) {
                valid = false;
            } else {
                code_point = (code_point << 6) | (continuation & 0x3F);
            }
        }
        valid = valid &&
            code_point >= minimum &&
            code_point <= 0x10FFFF &&
            !(code_point >= 0xD800 && code_point <= 0xDFFF);

        if (!valid) {
            output.push_back(REPLACEMENT_CHARACTER);
            ++index;
            continue;
        }

        if (code_point <= 0xFFFF) {
            output.push_back(static_cast<char16_t>(code_point));
        } else {
            code_point -= 0x10000;
            output.push_back(static_cast<char16_t>(0xD800 + (code_point >> 10)));
            output.push_back(static_cast<char16_t>(0xDC00 + (code_point & 0x3FF)));
        }
        index += length;
    }
    return output;
}

}  // namespace transcript
