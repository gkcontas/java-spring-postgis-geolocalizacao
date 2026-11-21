package com.gkcontas.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gkcontas.geo.place.PlaceHit;
import com.gkcontas.geo.place.PlaceQueries;
import com.gkcontas.geo.spatial.InvalidGeometryException;
import com.gkcontas.geo.zone.AreaComparison;
import com.gkcontas.geo.zone.DuplicateZoneException;
import com.gkcontas.geo.zone.Zone;
import com.gkcontas.geo.zone.ZoneService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ZonesIT extends IntegrationTestBase {

    /** About 2.1 km x 2.2 km around Paulista Avenue, as [longitude, latitude]. */
    static final String PAULISTA = """
            {"type": "Polygon", "coordinates": [[
              [-46.67, -23.57], [-46.65, -23.57], [-46.65, -23.55], [-46.67, -23.55], [-46.67, -23.57]
            ]]}""";

    @Autowired
    ZoneService zones;

    @Autowired
    PlaceQueries queries;

    @Autowired
    ObjectMapper json;

    JsonNode geo(String text) throws Exception {
        return json.readTree(text);
    }

    @Test
    void createsAZoneAndMeasuresItInMetres() throws Exception {
        Zone zone = zones.create("Paulista", geo(PAULISTA));

        var metrics = zones.metrics(zone.getId());
        // 0.02° of longitude at 23.56°S = 2040 m; 0.02° of latitude = 2213 m.
        assertThat(metrics.areaSquareMeters()).isCloseTo(2040 * 2213, within(30_000.0));
        assertThat(metrics.perimeterMeters()).isCloseTo(2 * (2040 + 2213), within(30.0));
        assertThat(zone.getArea().getSRID()).isEqualTo(4326);
    }

    @Test
    void coversIncludesTheBorderAndExcludesHoles() throws Exception {
        String withHole = """
                {"type": "Polygon", "coordinates": [
                  [[-46.67, -23.57], [-46.65, -23.57], [-46.65, -23.55], [-46.67, -23.55], [-46.67, -23.57]],
                  [[-46.662, -23.562], [-46.658, -23.562], [-46.658, -23.558], [-46.662, -23.558], [-46.662, -23.562]]
                ]}""";
        Zone zone = zones.create("Paulista without the park", geo(withHole));
        place("inside", -23.565, -46.665);
        place("on the border", -23.56, -46.65);
        place("in the hole", -23.56, -46.66);
        place("outside", -23.54, -46.66);

        assertThat(queries.inZone(zone.getId(), 10)).extracting(PlaceHit::name)
                .containsExactly("inside", "on the border");
        assertThat(queries.countInZone(zone.getId())).isEqualTo(2);
        assertThat(zones.covering(-23.56, -46.65)).extracting(ZoneService.ZoneSummary::name)
                .containsExactly("Paulista without the park");
        assertThat(zones.covering(-23.56, -46.66)).isEmpty();
    }

    @Test
    void refusesAnOpenRing() {
        assertThatThrownBy(() -> zones.create("open", geo("""
                {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1]]]}""")))
                .isInstanceOf(InvalidGeometryException.class)
                .hasMessage("ring 0 is not closed: first and last positions must be equal");
    }

    @Test
    void refusesARingWithTooFewPositions() {
        assertThatThrownBy(() -> zones.create("line", geo("""
                {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [0, 0]]]}""")))
                .isInstanceOf(InvalidGeometryException.class)
                .hasMessageContaining("at least 4 positions");
    }

    /** The bow tie: valid JSON, a closed ring, and a polygon that crosses itself. */
    @Test
    void refusesASelfIntersectingPolygonInsteadOfRepairingIt() {
        assertThatThrownBy(() -> zones.create("bow tie", geo("""
                {"type": "Polygon", "coordinates": [[[0, 0], [1, 1], [1, 0], [0, 1], [0, 0]]]}""")))
                .isInstanceOf(InvalidGeometryException.class)
                .hasMessage("invalid polygon: Self-intersection at [0.5, 0.5]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM zone", Long.class)).isZero();
    }

    @Test
    void refusesOtherTypesAndPositionsOutOfRange() {
        assertThatThrownBy(() -> zones.create("point", geo("""
                {"type": "Point", "coordinates": [0, 0]}""")))
                .hasMessage("area type must be Polygon");
        assertThatThrownBy(() -> zones.create("swapped", geo("""
                {"type": "Polygon", "coordinates": [[[-23.5, -146.6], [-23.4, -146.6], [-23.4, -146.5], [-23.5, -146.6]]]}""")))
                .hasMessageContaining("out of range");
    }

    @Test
    void refusesAPolygonWiderThanHalfTheWorld() {
        assertThatThrownBy(() -> zones.create("fiji", geo("""
                {"type": "Polygon", "coordinates": [[[179, -18], [-179, -18], [-179, -16], [179, -16], [179, -18]]]}""")))
                .hasMessageContaining("split it at the antimeridian");
    }

    @Test
    void refusesADuplicateName() throws Exception {
        zones.create("Paulista", geo(PAULISTA));

        assertThatThrownBy(() -> zones.create("Paulista", geo(PAULISTA))).isInstanceOf(DuplicateZoneException.class);
    }

    @Test
    void theDatabaseRefusesAnInvalidPolygonThatBypassesTheApi() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO zone (name, area) VALUES ('bow tie', ST_GeomFromText('POLYGON((0 0, 1 1, 1 0, 0 1, 0 0))', 4326))
                """)).hasStackTraceContaining("zone_area_valid");
    }

    /**
     * Web Mercator stretches both axes by 1/cos(latitude): at 23.56°S the
     * area comes out about 19% larger. The local UTM zone is within a
     * fraction of a percent of the spheroid.
     */
    @Test
    void areaDependsOnTheProjection() throws Exception {
        Zone zone = zones.create("Paulista", geo(PAULISTA));

        AreaComparison areas = zones.compareAreas(zone.getId());

        double inflation = 1 / Math.pow(Math.cos(Math.toRadians(23.56)), 2);
        assertThat(areas.utmSrid()).isEqualTo(32723);
        assertThat(areas.squareDegrees()).isCloseTo(0.0004, within(1e-9));
        assertThat(areas.webMercator() / areas.spheroid()).isCloseTo(inflation, within(0.01));
        assertThat(areas.utm() / areas.spheroid()).isCloseTo(1.0, within(0.002));
        assertThat(areas.sphere() / areas.spheroid()).isCloseTo(1.0, within(0.01));
    }
}
