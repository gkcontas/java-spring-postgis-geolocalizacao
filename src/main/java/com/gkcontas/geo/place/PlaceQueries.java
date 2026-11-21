package com.gkcontas.geo.place;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Spatial reads, in SQL: what the index can and cannot do is easier to see than through a query builder. */
@Component
public class PlaceQueries {

    /**
     * {@code wasNull()} answers for the last column read, so the nullable
     * distance is read into a boxed value right away; a primitive
     * {@code getDouble} would turn SQL NULL into 0.0.
     */
    public static final RowMapper<PlaceHit> HIT = (rs, i) -> new PlaceHit(rs.getLong("id"), rs.getString("name"),
            rs.getString("category"), rs.getDouble("latitude"), rs.getDouble("longitude"),
            rs.getObject("distance", Double.class));

    private final NamedParameterJdbcTemplate jdbc;

    public PlaceQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlaceHit> nearby(double latitude, double longitude, double radiusMeters, String category, int limit) {
        Map<String, Object> params = origin(latitude, longitude);
        params.put("radius", radiusMeters);
        params.put("limit", limit);
        String sql = PlaceSql.NEARBY;
        if (category != null) {
            sql += PlaceSql.CATEGORY_FILTER;
            params.put("category", category);
        }
        return jdbc.query(sql + PlaceSql.NEARBY_ORDER, params, HIT);
    }

    public List<PlaceHit> nearest(double latitude, double longitude, int k, Double maxDistanceMeters) {
        Map<String, Object> params = origin(latitude, longitude);
        params.put("k", k);
        if (maxDistanceMeters != null) {
            params.put("maxDistance", maxDistanceMeters);
        }
        return jdbc.query(PlaceSql.nearest(maxDistanceMeters != null), params, HIT);
    }

    /**
     * A map viewport. When it crosses the antimeridian the client sends
     * {@code west > east} (e.g. 170 to -170); ST_MakeEnvelope would quietly
     * swap them into -170..170, the 340 degrees the user is <em>not</em>
     * looking at. It becomes two boxes instead.
     */
    public Viewport viewport(double west, double south, double east, double north, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("west", west);
        params.put("south", south);
        params.put("east", east);
        params.put("north", north);
        params.put("limit", limit + 1);
        String box = west <= east ? PlaceSql.BOX : PlaceSql.BOX_ACROSS_ANTIMERIDIAN;
        List<PlaceHit> hits = jdbc.query(PlaceSql.VIEWPORT.formatted(box), params, HIT);
        boolean truncated = hits.size() > limit;
        return new Viewport(truncated ? hits.subList(0, limit) : hits, truncated);
    }

    public List<PlaceHit> inZone(long zoneId, int limit) {
        return jdbc.query(PlaceSql.IN_ZONE + " ORDER BY p.id LIMIT :limit", Map.of("zoneId", zoneId, "limit", limit), HIT);
    }

    public long countInZone(long zoneId) {
        Long count = jdbc.queryForObject(PlaceSql.COUNT_IN_ZONE, Map.of("zoneId", zoneId), Long.class);
        return count == null ? 0 : count;
    }

    static Map<String, Object> origin(double latitude, double longitude) {
        Map<String, Object> params = new HashMap<>();
        params.put("latitude", latitude);
        params.put("longitude", longitude);
        return params;
    }
}
