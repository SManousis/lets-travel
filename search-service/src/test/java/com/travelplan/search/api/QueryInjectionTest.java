package com.travelplan.search.api;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search text is sent to Elasticsearch only as a string value inside a fixed query structure, never parsed as
 * query syntax (no query_string) or spliced into JSON, so hostile text cannot change what is searched.
 */
class QueryInjectionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {
            "\"}}, {\"match_all\": {}}, {\"x\": \"",
            "title:* OR bookable:false",
            "*:*",
            "'; DROP TABLE travels; --",
            "<script>alert(1)</script>",
            "\u0000 \\\" \\\\"})
    void hostileTextStaysAPlainValue(String text) throws Exception {
        JsonNode query = JSON.readTree(JSON.writeValueAsString(TravelSearch.textQuery(text)));
        List<JsonNode> clauses = query.path("bool").path("should").valueStream().toList();
        assertThat(clauses).hasSize(3);
        for (JsonNode clause : clauses) {
            assertThat(clause.propertyNames()).containsExactly("multi_match");
            assertThat(clause.path("multi_match").path("query").asString()).isEqualTo(text);
        }
        assertThat(query.toString()).doesNotContain("query_string").doesNotContain("\"match_all\":{}}");
    }
}
