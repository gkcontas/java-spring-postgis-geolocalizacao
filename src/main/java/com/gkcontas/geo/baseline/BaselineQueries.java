package com.gkcontas.geo.baseline;

import com.gkcontas.geo.place.PlaceHit;
import com.gkcontas.geo.place.PlaceQueries;
import com.gkcontas.geo.place.PlaceSql;
import com.gkcontas.geo.spatial.PlanInspector;
import com.gkcontas.geo.spatial.QueryPlan;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Runs the production queries with and without their indexes, for the
 * comparison in the README. NO_INDEX is the same SQL: the planner is told not
 * to use index or bitmap scans with SET LOCAL, which lasts until the end of
 * the transaction and never leaks into the pool.
 * <p>
 * "The same SQL", but not the same text. After five executions pgJDBC turns
 * a statement into a server-side prepared statement and PostgreSQL caches
 * its plan per connection. A plan cached while indexes were allowed is
 * reused after SET enable_indexscan = off, so NO_INDEX would quietly run the
 * index plan. A comment makes it a different statement with its own cache.
 */
@Component
public class BaselineQueries {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PlanInspector plans;

    public BaselineQueries(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction, PlanInspector plans) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.plans = plans;
    }

    public record Query(String sql, Map<String, Object> params) {
    }

    public Query nearby(Strategy strategy, double latitude, double longitude, double radius) {
        String sql = strategy == Strategy.DISTANCE_FILTER ? PlaceSql.NEARBY_DISTANCE_FILTER : PlaceSql.NEARBY;
        return new Query(sql + " ORDER BY distance, p.id",
                Map.of("latitude", latitude, "longitude", longitude, "radius", radius));
    }

    public Query nearest(double latitude, double longitude, int k) {
        return new Query(PlaceSql.nearest(false), Map.of("latitude", latitude, "longitude", longitude, "k", k));
    }

    public Query inZone(long zoneId) {
        return new Query(PlaceSql.IN_ZONE + " ORDER BY p.id", Map.of("zoneId", zoneId));
    }

    public List<PlaceHit> run(Strategy strategy, Query query) {
        return transaction.execute(status -> {
            restrict(strategy);
            return jdbc.query(text(strategy, query), query.params(), PlaceQueries.HIT);
        });
    }

    public QueryPlan explain(Strategy strategy, Query query) {
        return transaction.execute(status -> {
            restrict(strategy);
            return plans.explain(text(strategy, query), query.params());
        });
    }

    private static String text(Strategy strategy, Query query) {
        return strategy == Strategy.NO_INDEX ? "/* no index */ " + query.sql() : query.sql();
    }

    private void restrict(Strategy strategy) {
        if (strategy == Strategy.NO_INDEX) {
            jdbc.getJdbcTemplate().execute("SET LOCAL enable_indexscan = off");
            jdbc.getJdbcTemplate().execute("SET LOCAL enable_bitmapscan = off");
        }
    }
}
