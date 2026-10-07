# json

A strict JSON parser and writer for [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259), plus
[RFC 6901](https://www.rfc-editor.org/rfc/rfc6901) JSON Pointer, in about 850 lines (Javadoc included) and with no
dependency besides the JDK. Values are a sealed interface of six records, numbers keep every digit that was written, and a parse error
tells you the line and column.

```java
JsonValue doc = JsonParser.parse("""
    {"Image": {"Width": 800, "IDs": [116, 943, 234, 38793]}}""");

doc.at("/Image/IDs/1");     // Optional[JsonNumber[value=943]]
doc.at("/Image/Height");    // Optional.empty
doc.toJson();               // {"Image":{"Width":800,"IDs":[116,943,234,38793]}}
doc.toPrettyJson(2);        // the same, indented two spaces per level

JsonParser.parse("{\"a\": [1, 2,]}");
// JsonParseException: line 1, column 13: unexpected character ']', expected a value
```

These snippets are not decoration: [`ReadmeExampleTest`](src/test/java/io/github/ectdev/json/ReadmeExampleTest.java)
runs them, so they cannot drift from the code.

| Type | What it is |
| --- | --- |
| [`JsonValue`](src/main/java/io/github/ectdev/json/JsonValue.java) | The sealed interface; its six implementations are `JsonNull`, `JsonBoolean`, `JsonNumber`, `JsonString`, `JsonArray` and `JsonObject`, all records |
| [`JsonParser`](src/main/java/io/github/ectdev/json/JsonParser.java) | Text to value: `parse(text)` and `parse(text, maxDepth)` |
| [`JsonWriter`](src/main/java/io/github/ectdev/json/JsonWriter.java) | Value to text, compact or indented |
| [`JsonParseException`](src/main/java/io/github/ectdev/json/JsonParseException.java) | What a bad text throws: `offset()`, `line()`, `column()` |
| `JsonPointer` | RFC 6901 evaluation, reached through `JsonValue.at(pointer)` |

## Decisions worth reading

**The six kinds of value are a closed set.** `JsonValue` is `sealed` and permits exactly six records. A `switch`
over it that handles all six needs no `default`, and the compiler rejects it when a case is missing. `JsonWriter` and
the pointer evaluation are written that way, so adding a seventh kind of value would break the build in every place
that has to learn about it, instead of falling quietly into a `default` that predates it.

**Numbers are `BigDecimal`, because a `double` changes them.** `0.1` read as a `double` is
0.1000000000000000055511151231257827…, and an integer above 2⁵³ (such as 9007199254740993) cannot be held at all.
A `BigDecimal` keeps exactly the digits written: `-122.026020` comes back with its trailing zero. The price is that
`equals` is scale-sensitive (`1.0` and `1.00` are different values; compare with `compareTo` on `value()` to ignore
that), and that `-0` becomes `0`, since a `BigDecimal` has no negative zero. An exponent that does not fit an `int`
is reported as a parse error rather than as a `NumberFormatException`.

**The grammar is the RFC's, with nothing extra.** No comments, no trailing commas, no single quotes, no unquoted
names, no `NaN` or `Infinity`, no leading zeros, no `+1`, no `.5`. Whitespace is exactly space, tab, line feed and
carriage return: a form feed, a no-break space and a byte order mark are all errors. Digits are ASCII only, on purpose:
`Character.isDigit` and `Character.digit` accept the digits of other scripts (a fullwidth １ parses as 1), which is
the classic way for a hand-written parser to accept text no other parser would. Tests feed it both a fullwidth digit
in a number and in a `\u` escape.

**Strings are always well-formed Unicode.** A Java `String` can hold half of a surrogate pair, but such a string has
no UTF-8 encoding, so it cannot be written to a file unchanged. `JsonString` refuses to be built from one, which
means every value that exists can be written and read back. An escaped surrogate pair (`😀`) is joined into
one character; an escaped half without its partner is an error; a raw pair is kept as it is.

**Duplicate member names are refused.** The RFC says names *should* be unique and that the behaviour of receivers is
unpredictable when they are not, which in practice means two parsers read the same text as two different documents.
Refusing is the one answer that cannot be exploited that way; the error points at the second occurrence.

**Hostile nesting is an error, not a crash.** The parser is recursive, so `[` repeated a million times would end in a
`StackOverflowError`, which is an `Error` and not something a caller should be asked to catch. Depth is limited to 512
by default (configurable) and the error says where the limit was crossed. A test feeds it a million brackets, and
another a hundred thousand `{"a":`.

**Errors say where.** `line 3, column 7: unexpected character '}', expected a member name`. Lines are counted by
line feed (so a carriage-return/line-feed pair is one line) and columns by UTF-16 code units from 1, which is the
index into the Java `String` plus one. A character outside the Basic Multilingual Plane therefore occupies two
columns; that is stated in the Javadoc and pinned by a test, because the alternative (counting code points) would not
match the `String` index a caller has in hand.

**A JSON Pointer has two kinds of failure, and they are kept apart.** A pointer that is malformed (no leading `/`, or a
`~` not followed by 0 or 1) is a programming error and throws `IllegalArgumentException`. A well-formed pointer that
leads nowhere (a missing member, an index past the end, a step into a number) is an ordinary answer and gives an
empty `Optional`. The whole pointer is checked before the walk starts, so `/nope/~` is reported as malformed even
though `/nope` already leads nowhere: whether a pointer is well-formed must not depend on the document. The escapes
are decoded in one left-to-right scan. Two `replace` calls give `~01` (the two characters `~1`) the wrong meaning if
`~0` is replaced first: it turns into `~1` and then into `/`. A scan has no order to remember. An array token is a valid index only if it is `0` or digits without a leading zero, so
`-` (the RFC's "element after the last"), `01`, `+1` and `1.0` lead nowhere.

**The writer escapes as little as the grammar allows.** The quote, the backslash and the controls below U+0020 (as
`\n`, `\t` and the like where a short form exists, otherwise a backslash, `u` and four lowercase hex digits). Everything
else is written as it is, so the output is as readable as the input and is meant to be encoded as UTF-8. That includes
U+2028 and U+2029, which are valid in JSON and, before ECMAScript 2019, were not valid inside a JavaScript string
literal: escape them yourself if you embed the output in a script of that age. Numbers use `BigDecimal.toString()`,
which can give exponent notation such as `1E+3`; that is valid JSON and reads back as the same `BigDecimal`.

## How it is checked

* **Written from the RFC, not from the parser.** The two documents of RFC 8259 section 13 and the whole pointer table
  of RFC 6901 section 5 are in the tests verbatim. The accept/reject tables list each bad text together with the line
  and column its error must name, counted by hand.
* **Randomised round trips with fixed seeds.** Eight seeds, 400 random values each (nested, with numbers of up to 89
  bits at scales from −30 to 30, and strings made from the characters that break naive code: quote, backslash, every
  kind of control character, DEL, U+2028/2029, a byte order mark, characters outside the BMP), each written at four
  indents and read back: the value must come out equal. Seeds appear in the failure message.
* **Damaged text.** Eight seeds, 600 documents each, with one character deleted, inserted or replaced, or the text cut
  off. The parser must either return a value (which must itself survive a round trip) or throw `JsonParseException`
  with an offset inside the text and a line and column that agree with the offset. A stack overflow or an index error
  fails the test. The test also asserts that a good share of the damaged texts still parse and that most are refused,
  because a fuzzer whose damage is always harmless, or always fatal, tests nothing.
* **Every prefix.** Every prefix of a valid document is either refused or a complete document; only the whole text is
  the latter.
* **Mutation checks.** Sixty-three deliberately broken copies of the code (a fullwidth digit accepted,
  a leading zero allowed, the nesting limit off by one, a surrogate half let through, the pointer escapes decoded in
  the wrong order, a writer that forgets the backslash, an object that no longer copies its map) are all caught; see
  [`tools/`](../tools/README.md). The first run killed 62 of the 63. The survivor was a real hole in the tests: the
  weakened array-index scan still rejected every bad token I had listed, because none of them happened to land on an
  existing element. `1-` does (10 − 3 = 7), so it and its relatives are now in the list.

## What is not covered

This is not run against an external conformance suite such as JSONTestSuite; the accept and reject tables were
written from the RFC grammar, and the damaged-text test checks consistency, not that every reject is *correct*. There
is no streaming API and no support for documents larger than memory, no comments or JSON5, no JSON Pointer mutation
(`add`, `remove`) and no relative pointers. Performance has not been measured, so nothing here claims it.

Run just this module with `./mvnw -pl json test`.
