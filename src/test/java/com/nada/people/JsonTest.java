package com.nada.people;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesObjectsArraysAndScalars() {
        Object parsed = Json.parse(" {\"a\": 1, \"b\": [true, false, null, \"x\"], \"c\": {\"d\": -2}} ");

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1L);
        expected.put("b", java.util.Arrays.asList(true, false, null, "x"));
        expected.put("c", Map.of("d", -2L));
        assertEquals(expected, parsed);
    }

    @Test
    void integersAndOtherNumbersAreDistinguished() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(0L, Json.parse("-0"));
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807"));
        assertEquals(new BigInteger("9223372036854775808"), Json.parse("9223372036854775808"));
        assertEquals(new BigDecimal("1.5"), Json.parse("1.5"));
        assertEquals(new BigDecimal("1E+2"), Json.parse("1e2"));
    }

    @Test
    void decodesEscapesAndSurrogatePairs() {
        assertEquals("a\"b\\c/d\b\f\n\r\t", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""));
        assertEquals("\u0906", Json.parse("\"\\u0906\""));
        assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
    }

    @Test
    void keepsRawUnicodeText() {
        assertEquals("आशा 😀 Zoë", Json.parse("\"आशा 😀 Zoë\""));
    }

    @Test
    void rejectsInvalidJson() {
        String[] invalid = {
            "", "   ", "{", "}", "{\"a\":}", "{\"a\" 1}", "{a:1}", "{'a':1}", "{\"a\":1,}", "[1,]", "[1 2]",
            "{} x", "{}{}", "nul", "truex", "01", "1.", ".5", "-", "+1", "1e", "\"abc", "\"a\nb\"", "\"\\x\"",
            "\"\\u12\"", "\"\\u12G4\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800a\"", "\"\\ude00\\ud83d\"",
        };
        for (String text : invalid) {
            assertThrows(Json.ParseException.class, () -> Json.parse(text), "should reject: [" + text + "]");
        }
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(1000) + "]".repeat(1000);
        assertThrows(Json.ParseException.class, () -> Json.parse(deep));
    }

    @Test
    void parsesJsonNullAtTopLevel() {
        assertNull(Json.parse("null"));
    }

    @Test
    void writesCompactJsonInInsertionOrder() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connected", true);
        map.put("hops", 2L);
        map.put("path", List.of(Map.of("id", 1L)));
        map.put("none", null);

        assertEquals("{\"connected\":true,\"hops\":2,\"path\":[{\"id\":1}],\"none\":null}", Json.write(map));
    }

    @Test
    void writesNonAsciiTextUnescapedAndEscapesSpecials() {
        assertEquals("\"आशा 😀\"", Json.write("आशा 😀"));
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.write("a\"b\\c\n\u0001"));
    }

    @Test
    void writeThenParseRoundTrips() {
        String tricky = "  \"quoted\" \\ \n\t \u0000 आशा 😀 ";
        assertEquals(tricky, Json.parse(Json.write(tricky)));
    }
}
