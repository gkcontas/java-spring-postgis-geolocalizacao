package com.gkcontas.geo;

import com.gkcontas.geo.place.PlaceHit;
import com.gkcontas.geo.place.PlaceQueries;
import com.gkcontas.geo.place.Viewport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlaceQueriesIT extends IntegrationTestBase {

    private static final double LAT = -23.5505;
    private static final double LNG = -46.6333;

    @Autowired
    PlaceQueries queries;

    @Test
    void nearbyReturnsWhatIsInsideTheRadiusNearestFirstWithDistancesInMetres() {
        place("far", LAT + north(1500), LNG);
        place("near", LAT + north(300), LNG);
        place("middle", LAT, LNG + east(800, LAT));

        List<PlaceHit> hits = queries.nearby(LAT, LNG, 1000, null, 50);

        assertThat(hits).extracting(PlaceHit::name).containsExactly("near", "middle");
        assertThat(hits.get(0).distanceMeters()).isCloseTo(300, within(3.0));
        assertThat(hits.get(1).distanceMeters()).isCloseTo(800, within(5.0));
    }

    @Test
    void nearbyFiltersByCategoryAndLimits() {
        places.create("pharmacy 1", "pharmacy", LAT + north(100), LNG);
        places.create("pharmacy 2", "pharmacy", LAT + north(200), LNG);
        places.create("bakery", "bakery", LAT + north(50), LNG);

        assertThat(queries.nearby(LAT, LNG, 1000, "pharmacy", 50)).extracting(PlaceHit::name)
                .containsExactly("pharmacy 1", "pharmacy 2");
        assertThat(queries.nearby(LAT, LNG, 1000, null, 1)).extracting(PlaceHit::name).containsExactly("bakery");
    }

    @Test
    void radiusIsACircleInMetresEvenNearThePole() {
        double lat = 60.0;
        double lng = 10.75;
        place("east 900 m", lat, lng + east(900, lat));
        place("north 900 m", lat + north(900), lng);
        place("east 1100 m", lat, lng + east(1100, lat));

        assertThat(queries.nearby(lat, lng, 1000, null, 50)).extracting(PlaceHit::name)
                .containsExactlyInAnyOrder("east 900 m", "north 900 m");
    }

    /**
     * At 60°N a degree of longitude is half a degree of latitude. A place
     * 0.010° east is 557 m away; one 0.006° north is 667 m away. Ordering by
     * geometry distance (degrees) puts the northern one first; geography gets
     * it right.
     */
    @Test
    void nearestOrdersByMetresWhereDegreesWouldGetItWrong() {
        double lat = 60.0;
        double lng = 10.75;
        place("east", lat, lng + 0.010);
        place("north", lat + 0.006, lng);

        List<String> byDegrees = jdbc.queryForList("""
                SELECT name FROM place
                ORDER BY location <-> ST_SetSRID(ST_MakePoint(?, ?), 4326)
                """, String.class, lng, lat);
        List<PlaceHit> byMetres = queries.nearest(lat, lng, 2, null);

        assertThat(byDegrees).containsExactly("north", "east");
        assertThat(byMetres).extracting(PlaceHit::name).containsExactly("east", "north");
        assertThat(byMetres.get(0).distanceMeters()).isCloseTo(557, within(3.0));
        assertThat(byMetres.get(1).distanceMeters()).isCloseTo(667, within(3.0));
    }

    @Test
    void nearestReturnsKAndHonoursTheMaximumDistance() {
        for (int i = 1; i <= 5; i++) {
            place("p" + i, LAT + north(i * 400), LNG);
        }

        assertThat(queries.nearest(LAT, LNG, 3, null)).extracting(PlaceHit::name).containsExactly("p1", "p2", "p3");
        assertThat(queries.nearest(LAT, LNG, 10, 1000.0)).extracting(PlaceHit::name).containsExactly("p1", "p2");
        assertThat(queries.nearest(LAT, LNG, 10, null)).hasSize(5);
    }

    @Test
    void viewportReturnsTheBoxAndFlagsTruncation() {
        place("inside 1", -23.55, -46.63);
        place("inside 2", -23.56, -46.62);
        place("outside", -22.90, -43.20);

        Viewport all = queries.viewport(-46.7, -23.6, -46.6, -23.5, 10);
        Viewport one = queries.viewport(-46.7, -23.6, -46.6, -23.5, 1);

        assertThat(all.places()).extracting(PlaceHit::name).containsExactly("inside 1", "inside 2");
        // No reference point, no distance: null, not 0.0.
        assertThat(all.places()).extracting(PlaceHit::distanceMeters).containsOnlyNulls();
        assertThat(all.truncated()).isFalse();
        assertThat(one.places()).hasSize(1);
        assertThat(one.truncated()).isTrue();
    }

    /**
     * Fiji straddles the antimeridian. The map shows 179°E to 179°W, sent as
     * west = 179, east = -179. A single ST_MakEnvelope(179, .., -179, ..)
     * becomes -179..179: the whole world except what the user is looking at.
     */
    @Test
    void viewportAcrossTheAntimeridianIsTwoBoxes() {
        place("Suva side", -17.0, 179.5);
        place("Lau side", -17.0, -179.5);
        place("Greenwich", -17.0, 0.0);

        Viewport viewport = queries.viewport(179, -18, -179, -16, 10);
        List<String> naive = jdbc.queryForList("""
                SELECT name FROM place WHERE location && ST_MakeEnvelope(179, -18, -179, -16, 4326) ORDER BY id
                """, String.class);

        assertThat(viewport.places()).extracting(PlaceHit::name).containsExactly("Suva side", "Lau side");
        assertThat(naive).containsExactly("Greenwich");
    }
}
