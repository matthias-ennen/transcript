#include "../../main/cpp/utf8_to_utf16.h"

#include <cassert>
#include <string>

int main() {
    using transcript::REPLACEMENT_CHARACTER;
    using transcript::utf8_to_utf16_replacing_invalid;

    assert(utf8_to_utf16_replacing_invalid("ASCII") == u"ASCII");
    assert(utf8_to_utf16_replacing_invalid("Grüße") == u"Grüße");
    assert(utf8_to_utf16_replacing_invalid("你好") == u"你好");
    assert(utf8_to_utf16_replacing_invalid("مرحبا") == u"مرحبا");
    assert(utf8_to_utf16_replacing_invalid("🤖") == u"🤖");

    std::string token_boundary;
    token_boundary.append("\xF0\x9F", 2);
    token_boundary.append("\xA4\x96", 2);
    assert(utf8_to_utf16_replacing_invalid(token_boundary) == u"🤖");

    const auto incomplete = utf8_to_utf16_replacing_invalid(std::string("\xE2\x82", 2));
    assert(!incomplete.empty());
    assert(incomplete.front() == REPLACEMENT_CHARACTER);

    const auto invalid = utf8_to_utf16_replacing_invalid(std::string("A\xFF" "B", 3));
    assert(invalid == std::u16string({u'A', REPLACEMENT_CHARACTER, u'B'}));

    const auto overlong = utf8_to_utf16_replacing_invalid(std::string("\xC0\xAF", 2));
    assert(!overlong.empty());
    assert(overlong.front() == REPLACEMENT_CHARACTER);
}
