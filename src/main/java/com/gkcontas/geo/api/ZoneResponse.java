package com.gkcontas.geo.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.gkcontas.geo.spatial.GeoJson;
import com.gkcontas.geo.zone.Zone;
import com.gkcontas.geo.zone.ZoneMetrics;

import java.time.Instant;

public record ZoneResponse(long id, String name, JsonNode area, double areaSquareMeters, double perimeterMeters,
                           Instant createdAt) {

    static ZoneResponse of(Zone zone, ZoneMetrics metrics) {
        return new ZoneResponse(zone.getId(), zone.getName(), GeoJson.write(zone.getArea()),
                metrics.areaSquareMeters(), metrics.perimeterMeters(), zone.getCreatedAt());
    }
}
