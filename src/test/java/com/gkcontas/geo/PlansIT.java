package com.gkcontas.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gkcontas.geo.baseline.BaselineQueries;
import com.gkcontas.geo.baseline.Strategy;
import com.gkcontas.geo.place.PlaceHit;
import com.gkcontas.geo.seed.PlaceSeeder;
import com.gkcontas.geo.spatial.PlanInspector;
import com.gkcontas.geo.spatial.QueryPlan;
import com.gkcontas.geo.zone.ZoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the planner does with each query, read from EXPLAIN. The table gets
 * 20 000 places and fresh statistics first: on a handful of rows a
 * sequential scan is the right plan and the test would prove nothing.
 */
class PlansIT extends IntegrationTestBase {

    private static final double LAT = -23.55;
    private static final double LNG = -46.63;

    @Autowired
    PlaceSeeder seeder;

    @Autowired
    BaselineQueries baseline;

    @Autowired
    PlanInspector inspector;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ZoneService zones;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void seed() {
        seeder.seed(20_000);
    }

    @Test
    void radiusUsesTheGeographyExpressionIndex() {
        QueryPlan plan = baseline.explain(Strategy.INDEXED, baseline.nearby(Strategy.INDEXED, LAT, LNG, 1000));

        assertThat(plan.indexes()).containsOnly("place_location_geog_gist");
        assertThat(plan.has("Seq Scan")).isFalse();
    }

    @Test
    void distanceInTheWhereClauseScansTheWholeTable() {
        QueryPlan plan = baseline.explain(Strategy.DISTANCE_FILTER,
                baseline.nearby(Strategy.DISTANCE_FILTER, LAT, LNG, 1000));

        assertThat(plan.has("Seq Scan")).isTrue();
        assertThat(plan.indexes()).isEmpty();
    }

    /**
     * With only the index on the geometry column, a predicate on
     * location::geography is a different expression: the planner does not use
     * it. DDL is transactional in PostgreSQL, so the index is dropped and
     * restored by a rollback.
     */
    @Test
    void castingToGeographyIgnoresTheGeometryIndex() {
        BaselineQueries.Query query = baseline.nearby(Strategy.INDEXED, LAT, LNG, 1000);

        QueryPlan plan = transaction.execute(status -> {
            jdbc.execute("DROP INDEX place_location_geog_gist");
            QueryPlan inside = inspector.explain(query.sql(), query.params());
            status.setRollbackOnly();
            return inside;
        });

        assertThat(plan.has("Seq Scan")).isTrue();
        assertThat(plan.indexes()).doesNotContain("place_location_gist");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'place_location_geog_gist'",
                Long.class)).isOne();
    }

    @Test
    void nearestIsAnOrderedIndexScanWithoutASort() {
        QueryPlan plan = baseline.explain(Strategy.INDEXED, baseline.nearest(LAT, LNG, 10));

        assertThat(plan.nodes()).containsExactly("Limit", "Index Scan");
        assertThat(plan.indexes()).containsExactly("place_location_geog_gist");
    }

    @Test
    void withoutIndexesNearestSortsEveryRow() {
        QueryPlan plan = baseline.explain(Strategy.NO_INDEX, baseline.nearest(LAT, LNG, 10));

        assertThat(plan.has("Sort")).isTrue();
        assertThat(plan.has("Seq Scan")).isTrue();
        assertThat(plan.indexes()).isEmpty();
    }

    @Test
    void polygonSearchUsesTheGeometryIndex() throws Exception {
        long zone = zones.create("Paulista", json.readTree(ZonesIT.PAULISTA)).getId();
        seeder.analyze();

        QueryPlan indexed = baseline.explain(Strategy.INDEXED, baseline.inZone(zone));
        QueryPlan unindexed = baseline.explain(Strategy.NO_INDEX, baseline.inZone(zone));

        assertThat(indexed.indexes()).contains("place_location_gist");
        assertThat(unindexed.indexes()).isEmpty();
        assertThat(unindexed.totalCost()).isGreaterThan(indexed.totalCost());
    }

    @Test
    void everyStrategyReturnsTheSameRows() throws Exception {
        long zone = zones.create("Paulista", json.readTree(ZonesIT.PAULISTA)).getId();

        for (BaselineQueries.Query query : List.of(baseline.nearby(Strategy.INDEXED, LAT, LNG, 1500),
                baseline.nearest(LAT, LNG, 25), baseline.inZone(zone))) {
            List<Long> indexed = ids(baseline.run(Strategy.INDEXED, query));
            assertThat(indexed).isNotEmpty();
            assertThat(ids(baseline.run(Strategy.NO_INDEX, query))).isEqualTo(indexed);
        }
        List<Long> dwithin = ids(baseline.run(Strategy.INDEXED, baseline.nearby(Strategy.INDEXED, LAT, LNG, 1500)));
        assertThat(ids(baseline.run(Strategy.DISTANCE_FILTER,
                baseline.nearby(Strategy.DISTANCE_FILTER, LAT, LNG, 1500)))).isEqualTo(dwithin);
    }

    @Test
    void noIndexSettingDoesNotLeakIntoThePool() {
        baseline.run(Strategy.NO_INDEX, baseline.nearest(LAT, LNG, 10));

        for (int i = 0; i < 20; i++) {
            assertThat(jdbc.queryForObject("SHOW enable_indexscan", String.class)).isEqualTo("on");
        }
        assertThat(inspector.explain(baseline.nearest(LAT, LNG, 10).sql(), Map.of("latitude", LAT, "longitude", LNG,
                "k", 10)).indexes()).isNotEmpty();
    }

    /**
     * After five executions pgJDBC switches a statement to a server-side
     * prepared statement, and PostgreSQL caches its plan. A cached plan built
     * with indexes allowed ignores a later SET enable_indexscan = off: the
     * "no index" comparison would silently run the index plan. Checked through
     * the transaction's own scan counters, not through timing.
     */
    @Test
    void noIndexReallyScansEvenAfterTheIndexedPlanWasCachedOnTheConnection() {
        BaselineQueries.Query query = baseline.nearby(Strategy.INDEXED, LAT, LNG, 1000);

        transaction.execute(status -> {
            for (int i = 0; i < 10; i++) {
                baseline.run(Strategy.INDEXED, query);
            }
            long before = sequentialScans();
            baseline.run(Strategy.NO_INDEX, query);
            assertThat(sequentialScans()).isGreaterThan(before);
            status.setRollbackOnly();
            return null;
        });
    }

    private long sequentialScans() {
        Long scans = jdbc.queryForObject("SELECT seq_scan FROM pg_stat_xact_user_tables WHERE schemaname = 'public' AND relname = 'place'",
                Long.class);
        return scans == null ? 0 : scans;
    }

    private static List<Long> ids(List<PlaceHit> hits) {
        return hits.stream().map(PlaceHit::id).toList();
    }
}
