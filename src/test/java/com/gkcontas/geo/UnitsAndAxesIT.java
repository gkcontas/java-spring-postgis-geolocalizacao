package com.gkcontas.geo;

import com.gkcontas.geo.place.Place;
import com.gkcontas.geo.place.PlaceRepository;
import com.gkcontas.geo.spatial.Geometries;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Characterisation tests: what PostGIS actually answers when the unit, the
 * axis order or the SRID is wrong. None of these mistakes raises an error
 * except the SRID ones; the others return plausible numbers.
 */
class UnitsAndAxesIT extends IntegrationTestBase {

    private static final double SAO_PAULO_LAT = -23.5505;
    private static final double SAO_PAULO_LNG = -46.6333;

    @Autowired
    PlaceRepository repository;

    @Test
    void distanceOnGeometryIsInDegreesAndOnGeographyInMetres() {
        double lat2 = SAO_PAULO_LAT + north(1000);
        String sql = "SELECT ST_Distance(%s, %s)";
        String a = "ST_SetSRID(ST_MakePoint(" + SAO_PAULO_LNG + ", " + SAO_PAULO_LAT + "), 4326)";
        String b = "ST_SetSRID(ST_MakePoint(" + SAO_PAULO_LNG + ", " + lat2 + "), 4326)";

        Double degrees = jdbc.queryForObject(sql.formatted(a, b), Double.class);
        Double metres = jdbc.queryForObject(sql.formatted(a + "::geography", b + "::geography"), Double.class);

        assertThat(degrees).isCloseTo(0.009, within(0.0001));
        // north() uses the mean length of a degree of latitude; at 23.5°S it is 0.35% shorter.
        assertThat(metres).isCloseTo(1000, within(5.0));
    }

    /**
     * ST_DWithin(geometry, geometry, 0.01) at 60°N: 0.01 degree is about
     * 1.1 km to the north but only 556 m to the east. A "radius" in degrees is
     * an ellipse that gets thinner towards the poles.
     */
    @Test
    void radiusInDegreesIsAnEllipse() {
        double lat = 60.0;
        double lng = 10.75;
        String origin = "ST_SetSRID(ST_MakePoint(" + lng + ", " + lat + "), 4326)";
        String northPoint = "ST_SetSRID(ST_MakePoint(" + lng + ", " + (lat + north(900)) + "), 4326)";
        String eastPoint = "ST_SetSRID(ST_MakePoint(" + (lng + east(900, lat)) + ", " + lat + "), 4326)";

        String inDegrees = "SELECT ST_DWithin(%s, %s, 0.01)";
        String inMetres = "SELECT ST_DWithin(%s::geography, %s::geography, 1000)";

        assertThat(jdbc.queryForObject(inDegrees.formatted(origin, northPoint), Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(inDegrees.formatted(origin, eastPoint), Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(inMetres.formatted(origin, northPoint), Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(inMetres.formatted(origin, eastPoint), Boolean.class)).isTrue();
    }

    @Test
    void areaOnGeometryIsInSquareDegrees() {
        String square = "ST_MakeEnvelope(0, 0, 0.01, 0.01, 4326)";

        Double degrees = jdbc.queryForObject("SELECT ST_Area(" + square + ")", Double.class);
        Double metres = jdbc.queryForObject("SELECT ST_Area(" + square + "::geography)", Double.class);

        assertThat(degrees).isCloseTo(0.0001, within(1e-9));
        // 1113.2 m of longitude x 1105.7 m of latitude at the equator.
        assertThat(metres).isCloseTo(1_230_900, within(2_000.0));
    }

    /**
     * Latitude and longitude swapped: São Paulo becomes (-46.6, -23.6), a
     * point in the South Atlantic that passes any range check because both
     * values are valid latitudes and longitudes.
     */
    @Test
    void swappedAxesLandInTheOceanAndPassRangeValidation() {
        Double distance = jdbc.queryForObject("""
                SELECT ST_Distance(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                                   ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """, Double.class, SAO_PAULO_LNG, SAO_PAULO_LAT, SAO_PAULO_LAT, SAO_PAULO_LNG);

        assertThat(SAO_PAULO_LNG).isBetween(-90.0, 90.0);
        assertThat(distance).isGreaterThan(2_500_000);
    }

    @Test
    void theFactoryPutsLongitudeInXAndLatitudeInY() {
        Place saved = place("Sé", SAO_PAULO_LAT, SAO_PAULO_LNG);

        Double x = jdbc.queryForObject("SELECT ST_X(location) FROM place WHERE id = ?", Double.class, saved.getId());
        Double y = jdbc.queryForObject("SELECT ST_Y(location) FROM place WHERE id = ?", Double.class, saved.getId());

        assertThat(x).isEqualTo(SAO_PAULO_LNG);
        assertThat(y).isEqualTo(SAO_PAULO_LAT);
        assertThat(saved.getLocation().getSRID()).isEqualTo(4326);
    }

    /**
     * {@code new GeometryFactory()} means SRID 0, "unknown". A typed column
     * does not refuse it: PostGIS takes SRID 0 as "use the column's SRID".
     * Right by accident when the coordinates are longitude and latitude...
     */
    @Test
    void aPointWithoutSridTakesTheColumnSrid() {
        GeometryFactory withoutSrid = new GeometryFactory();
        Place saved = repository.saveAndFlush(new Place("No SRID", "restaurant",
                withoutSrid.createPoint(new Coordinate(SAO_PAULO_LNG, SAO_PAULO_LAT))));

        assertThat(jdbc.queryForObject("SELECT ST_SRID(location) FROM place WHERE id = ?", Integer.class, saved.getId()))
                .isEqualTo(4326);
    }

    /**
     * ...and wrong when they are not: São Paulo in UTM 23S metres, without an
     * SRID, would be stored as a "longitude" of 333 000. Only the range
     * constraint stands between that and the table.
     */
    @Test
    void aProjectedPointWithoutSridIsCaughtOnlyByTheRangeConstraint() {
        GeometryFactory withoutSrid = new GeometryFactory();
        Place utm = new Place("UTM metres", "restaurant", withoutSrid.createPoint(new Coordinate(333_000, 7_394_000)));

        assertThatThrownBy(() -> repository.saveAndFlush(utm)).hasStackTraceContaining("place_location_in_range");
    }

    /** What the typed column does refuse is a different, known SRID. */
    @Test
    void aPointInAnotherSridIsRefusedByTheColumn() {
        assertThat(jdbc.queryForObject("SELECT ST_SRID(ST_MakePoint(-46.6, -23.5))", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO place (name, category, location)
                VALUES ('Web Mercator', 'x', ST_Transform(ST_SetSRID(ST_MakePoint(-46.6, -23.5), 4326), 3857))
                """)).hasStackTraceContaining("SRID (3857) does not match column SRID (4326)");
    }

    @Test
    void mixingSridsIsAnError() {
        assertThatThrownBy(() -> jdbc.queryForObject("""
                SELECT ST_Distance(ST_SetSRID(ST_MakePoint(0, 0), 4326), ST_SetSRID(ST_MakePoint(0, 0), 3857))
                """, Double.class))
                .hasStackTraceContaining("mixed SRID");
    }

    @Test
    void aPointOnTheBorderIsCoveredButNotContained() {
        String square = "ST_MakeEnvelope(0, 0, 1, 1, 4326)";
        String onEdge = "ST_SetSRID(ST_MakePoint(1, 0.5), 4326)";

        assertThat(jdbc.queryForObject("SELECT ST_Contains(" + square + ", " + onEdge + ")", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT ST_Covers(" + square + ", " + onEdge + ")", Boolean.class)).isTrue();
    }

    @Test
    void theRangeConstraintRejectsAPointOutOfTheWorld() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO place (name, category, location) VALUES ('Nowhere', 'x', ST_SetSRID(ST_MakePoint(200, 0), 4326))
                """)).hasStackTraceContaining("place_location_in_range");
        assertThat(Geometries.point(1, 2).getY()).isEqualTo(1);
    }
}
