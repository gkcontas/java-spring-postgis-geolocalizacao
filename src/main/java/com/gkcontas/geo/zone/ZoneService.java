package com.gkcontas.geo.zone;

import com.fasterxml.jackson.databind.JsonNode;
import com.gkcontas.geo.place.NotFoundException;
import com.gkcontas.geo.spatial.GeoJson;
import com.gkcontas.geo.spatial.InvalidGeometryException;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ZoneService {

    private final ZoneRepository zones;
    private final NamedParameterJdbcTemplate jdbc;

    public ZoneService(ZoneRepository zones, NamedParameterJdbcTemplate jdbc) {
        this.zones = zones;
        this.jdbc = jdbc;
    }

    @Transactional
    public Zone create(String name, JsonNode area) {
        Polygon polygon = GeoJson.readPolygon(area);
        if (zones.existsByName(name)) {
            throw new DuplicateZoneException(name);
        }
        try {
            return zones.saveAndFlush(new Zone(name, polygon));
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("zone_name_key")) {
                throw new DuplicateZoneException(name);
            }
            if (message.contains("zone_area_valid")) {
                // JTS and GEOS agree almost always; this is the "almost".
                throw new InvalidGeometryException("invalid polygon: rejected by the database validity check");
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Zone get(long id) {
        return zones.findById(id).orElseThrow(() -> new NotFoundException("zone " + id + " not found"));
    }

    public ZoneMetrics metrics(long id) {
        return jdbc.queryForObject("""
                SELECT ST_Area(area::geography) AS area, ST_Perimeter(area::geography) AS perimeter
                FROM zone WHERE id = :id
                """, Map.of("id", id), (rs, i) -> new ZoneMetrics(rs.getDouble("area"), rs.getDouble("perimeter")));
    }

    public AreaComparison compareAreas(long id) {
        Point centroid = get(id).getArea().getCentroid();
        int utm = utmSrid(centroid.getY(), centroid.getX());
        return jdbc.queryForObject("""
                SELECT ST_Area(area)                       AS degrees,
                       ST_Area(area::geography)            AS spheroid,
                       ST_Area(area::geography, false)     AS sphere,
                       ST_Area(ST_Transform(area, 3857))   AS mercator,
                       ST_Area(ST_Transform(area, :utm))   AS utm
                FROM zone WHERE id = :id
                """, Map.of("id", id, "utm", utm), (rs, i) -> new AreaComparison(rs.getDouble("degrees"),
                rs.getDouble("spheroid"), rs.getDouble("sphere"), rs.getDouble("mercator"), rs.getDouble("utm"), utm));
    }

    /** Zones that cover a point, boundary included: "do you deliver here?". */
    public List<ZoneSummary> covering(double latitude, double longitude) {
        return jdbc.query("""
                SELECT id, name FROM zone
                WHERE ST_Covers(area, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326))
                ORDER BY name
                """, Map.of("latitude", latitude, "longitude", longitude),
                (rs, i) -> new ZoneSummary(rs.getLong("id"), rs.getString("name")));
    }

    /** WGS 84 / UTM: 326xx in the northern hemisphere, 327xx in the southern, xx from 1 to 60. */
    static int utmSrid(double latitude, double longitude) {
        int zone = (int) Math.floor((longitude + 180) / 6) + 1;
        return (latitude < 0 ? 32700 : 32600) + Math.min(zone, 60);
    }

    public record ZoneSummary(long id, String name) {
    }
}
