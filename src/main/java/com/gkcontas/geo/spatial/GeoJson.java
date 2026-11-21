package com.gkcontas.geo.spatial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoJSON polygons (RFC 7946) in and out. Positions are {@code [longitude,
 * latitude]}, same order as JTS and PostGIS.
 * <p>
 * Reading is strict on purpose. An open ring is refused instead of closed for
 * the client, and a self-intersecting polygon is refused instead of repaired
 * with {@code ST_MakeValid}: a repaired "bow tie" becomes two triangles, a
 * different area from the one the user drew, and the zone would answer
 * questions about a shape nobody asked for.
 */
public final class GeoJson {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private GeoJson() {
    }

    public static Polygon readPolygon(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidGeometryException("area must be a GeoJSON object");
        }
        if (!"Polygon".equals(node.path("type").asText())) {
            throw new InvalidGeometryException("area type must be Polygon");
        }
        JsonNode rings = node.path("coordinates");
        if (!rings.isArray() || rings.isEmpty()) {
            throw new InvalidGeometryException("area coordinates must be a non-empty array of rings");
        }
        LinearRing shell = ring(rings.get(0), 0);
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i), i);
        }
        Polygon polygon = Geometries.FACTORY.createPolygon(shell, holes);

        // Not an error PostGIS would raise: a polygon from 170 to -170 is a
        // valid shape that wraps the other way round the globe, 340 degrees
        // wide. Geometry has no notion of the antimeridian.
        Envelope envelope = polygon.getEnvelopeInternal();
        if (envelope.getWidth() > 180) {
            throw new InvalidGeometryException(
                    "area spans more than 180 degrees of longitude; split it at the antimeridian");
        }

        TopologyValidationError error = new IsValidOp(polygon).getValidationError();
        if (error != null) {
            Coordinate at = error.getCoordinate();
            throw new InvalidGeometryException("invalid polygon: " + error.getMessage()
                    + (at == null ? "" : " at [" + at.getX() + ", " + at.getY() + "]"));
        }
        return polygon;
    }

    private static LinearRing ring(JsonNode positions, int index) {
        if (!positions.isArray() || positions.size() < 4) {
            throw new InvalidGeometryException("ring " + index + " needs at least 4 positions (a closed triangle)");
        }
        List<Coordinate> coordinates = new ArrayList<>(positions.size());
        for (JsonNode position : positions) {
            coordinates.add(position(position, index));
        }
        if (!coordinates.getFirst().equals2D(coordinates.getLast())) {
            throw new InvalidGeometryException("ring " + index + " is not closed: first and last positions must be equal");
        }
        return Geometries.FACTORY.createLinearRing(coordinates.toArray(Coordinate[]::new));
    }

    private static Coordinate position(JsonNode position, int ring) {
        if (!position.isArray() || position.size() < 2 || !position.get(0).isNumber() || !position.get(1).isNumber()) {
            throw new InvalidGeometryException("ring " + ring + " has a position that is not [longitude, latitude]");
        }
        double longitude = position.get(0).asDouble();
        double latitude = position.get(1).asDouble();
        if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new InvalidGeometryException("ring " + ring + " has a position out of range: [" + longitude + ", "
                    + latitude + "]; positions are [longitude, latitude]");
        }
        return new Coordinate(longitude, latitude);
    }

    public static ObjectNode write(Polygon polygon) {
        ObjectNode node = JSON.objectNode();
        node.put("type", "Polygon");
        ArrayNode rings = node.putArray("coordinates");
        rings.add(positions(polygon.getExteriorRing().getCoordinates()));
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            rings.add(positions(polygon.getInteriorRingN(i).getCoordinates()));
        }
        return node;
    }

    private static ArrayNode positions(Coordinate[] coordinates) {
        ArrayNode ring = JSON.arrayNode();
        for (Coordinate c : coordinates) {
            ring.addArray().add(c.getX()).add(c.getY());
        }
        return ring;
    }
}
