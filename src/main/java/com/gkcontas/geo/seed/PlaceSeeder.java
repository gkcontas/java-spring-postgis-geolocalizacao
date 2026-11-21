package com.gkcontas.geo.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk load for measurements: random places over greater São Paulo, generated
 * inside PostgreSQL with a fixed seed so every run gets the same points.
 */
@Component
public class PlaceSeeder {

    public static final double SOUTH = -23.80;
    public static final double NORTH = -23.35;
    public static final double WEST = -46.85;
    public static final double EAST = -46.35;

    private final JdbcTemplate jdbc;

    public PlaceSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Seeded(int count, long millis) {
    }

    @Transactional
    public Seeded seed(int count) {
        long start = System.nanoTime();
        jdbc.execute("TRUNCATE place, zone RESTART IDENTITY");
        jdbc.queryForObject("SELECT setseed(0.43)", Object.class);
        jdbc.update("""
                INSERT INTO place (name, category, location)
                SELECT 'Place ' || n,
                       (ARRAY['restaurant','pharmacy','bakery','gym','school','market','hospital','bank'])[1 + floor(random() * 8)::int],
                       ST_SetSRID(ST_MakePoint(? + random() * (? - ?), ? + random() * (? - ?)), 4326)
                FROM generate_series(1, ?) AS n
                """, WEST, EAST, WEST, SOUTH, NORTH, SOUTH, count);
        analyze();
        return new Seeded(count, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * After a bulk load the planner still has the statistics of the empty
     * table and can misjudge how selective a spatial predicate is. Autovacuum
     * would get there eventually; a load job should not wait for it.
     */
    public void analyze() {
        jdbc.execute("ANALYZE place");
        jdbc.execute("ANALYZE zone");
    }
}
