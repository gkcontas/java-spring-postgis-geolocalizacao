package com.gkcontas.geo.spatial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs {@code EXPLAIN (FORMAT JSON)} and walks the plan tree. */
@Component
public class PlanInspector {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public PlanInspector(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public QueryPlan explain(String sql, Map<String, ?> params) {
        String raw = jdbc.queryForObject("EXPLAIN (FORMAT JSON) " + sql, params, String.class);
        try {
            JsonNode root = json.readTree(raw).get(0).get("Plan");
            List<String> nodes = new ArrayList<>();
            List<String> indexes = new ArrayList<>();
            walk(root, nodes, indexes);
            return new QueryPlan(nodes, indexes, root.path("Total Cost").asDouble());
        } catch (Exception e) {
            throw new IllegalStateException("unreadable plan: " + raw, e);
        }
    }

    private static void walk(JsonNode node, List<String> nodes, List<String> indexes) {
        nodes.add(node.path("Node Type").asText());
        if (node.has("Index Name")) {
            indexes.add(node.get("Index Name").asText());
        }
        for (JsonNode child : node.path("Plans")) {
            walk(child, nodes, indexes);
        }
    }
}
