package com.gkcontas.geo.place;

/**
 * The spatial SQL, in one place so the API and the comparison endpoints run
 * exactly the same text. Every point parameter is built as
 * {@code ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)}: x first.
 */
public final class PlaceSql {

    static final String COLUMNS = "p.id, p.name, p.category, ST_Y(p.location) AS latitude, ST_X(p.location) AS longitude";

    static final String ORIGIN = "ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)";

    /**
     * Radius in metres. ST_DWithin on geography is index-assisted through
     * {@code place_location_geog_gist}: it expands the search box by the radius
     * and only computes exact distances for the candidates inside it.
     */
    public static final String NEARBY = """
            SELECT %s, ST_Distance(p.location::geography, %s::geography) AS distance
            FROM place p
            WHERE ST_DWithin(p.location::geography, %s::geography, :radius)
            """.formatted(COLUMNS, ORIGIN, ORIGIN);

    /** Same answer as {@link #NEARBY}, but ST_Distance in a WHERE is a plain function call: every row is computed. */
    public static final String NEARBY_DISTANCE_FILTER = """
            SELECT %s, ST_Distance(p.location::geography, %s::geography) AS distance
            FROM place p
            WHERE ST_Distance(p.location::geography, %s::geography) <= :radius
            """.formatted(COLUMNS, ORIGIN, ORIGIN);

    static final String NEARBY_ORDER = " ORDER BY distance, p.id LIMIT :limit";

    static final String CATEGORY_FILTER = " AND p.category = :category";

    /**
     * k nearest. {@code <->} in ORDER BY lets the GiST index return rows in
     * distance order and stop after k; on geography it is the distance on a
     * sphere, the reported distance is on the spheroid.
     */
    public static final String NEAREST = """
            SELECT %s, ST_Distance(p.location::geography, %s::geography) AS distance
            FROM place p
            %%s
            ORDER BY p.location::geography <-> %s::geography
            LIMIT :k
            """.formatted(COLUMNS, ORIGIN, ORIGIN);

    static final String NEAREST_WITHIN = "WHERE ST_DWithin(p.location::geography, " + ORIGIN + "::geography, :maxDistance)";

    /** Map viewport, in degrees: the bounding-box operator on the geometry index. */
    static final String VIEWPORT = """
            SELECT %s, NULL::double precision AS distance
            FROM place p
            WHERE %%s
            ORDER BY p.id
            LIMIT :limit
            """.formatted(COLUMNS);

    static final String BOX = "p.location && ST_MakeEnvelope(:west, :south, :east, :north, 4326)";

    static final String BOX_ACROSS_ANTIMERIDIAN = "(p.location && ST_MakeEnvelope(:west, :south, 180, :north, 4326)"
            + " OR p.location && ST_MakeEnvelope(-180, :south, :east, :north, 4326))";

    /** Places a zone covers, boundary included. */
    public static final String IN_ZONE = """
            SELECT %s, NULL::double precision AS distance
            FROM zone z JOIN place p ON ST_Covers(z.area, p.location)
            WHERE z.id = :zoneId
            """.formatted(COLUMNS);

    public static final String COUNT_IN_ZONE = """
            SELECT count(*)
            FROM zone z JOIN place p ON ST_Covers(z.area, p.location)
            WHERE z.id = :zoneId
            """;

    private PlaceSql() {
    }

    public static String nearest(boolean bounded) {
        return NEAREST.formatted(bounded ? NEAREST_WITHIN : "");
    }
}
