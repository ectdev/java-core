package io.github.ectdev.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The snippet at the top of the module README, run as written, with its comments turned into assertions. */
class ReadmeExampleTest {

    @Test
    void theSnippetInTheReadmeDoesWhatItsCommentsSay() {
        JsonValue doc = JsonParser.parse("""
            {"Image": {"Width": 800, "IDs": [116, 943, 234, 38793]}}""");

        assertEquals("Optional[JsonNumber[value=943]]", doc.at("/Image/IDs/1").toString());
        assertEquals("Optional.empty", doc.at("/Image/Height").toString());
        assertEquals("{\"Image\":{\"Width\":800,\"IDs\":[116,943,234,38793]}}", doc.toJson());

        String pretty = doc.toPrettyJson(2);
        assertEquals("""
            {
              "Image": {
                "Width": 800,
                "IDs": [
                  116,
                  943,
                  234,
                  38793
                ]
              }
            }""", pretty);
        assertEquals(doc, JsonParser.parse(pretty));

        JsonParseException e = assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\": [1, 2,]}"));
        assertEquals("line 1, column 13: unexpected character ']', expected a value", e.getMessage());
    }
}
