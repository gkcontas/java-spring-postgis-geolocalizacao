package com.gkcontas.geo.spatial;

import java.util.List;

/**
 * What EXPLAIN says about a query, reduced to what matters here: which plan
 * nodes ran and which indexes they used.
 */
public record QueryPlan(List<String> nodes, List<String> indexes, double totalCost) {

    public boolean usesIndex(String name) {
        return indexes.contains(name);
    }

    public boolean has(String nodeType) {
        return nodes.contains(nodeType);
    }
}
