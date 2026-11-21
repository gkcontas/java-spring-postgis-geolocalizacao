package com.gkcontas.geo.spatial;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * The only place that builds JTS geometries. Two traps live here:
 * <ul>
 *   <li>{@code new GeometryFactory()} creates geometries with SRID 0, and a
 *   {@code geometry(Point, 4326)} column does not refuse them: PostGIS reads 0
 *   as "the column's SRID", whatever the coordinates really are;</li>
 *   <li>{@link Coordinate} is (x, y), which for WGS 84 is (longitude,
 *   latitude) &mdash; the opposite of how people say coordinates out loud.</li>
 * </ul>
 * Callers pass latitude and longitude by name and never touch x and y.
 */
public final class Geometries {

    public static final int WGS84 = 4326;

    public static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), WGS84);

    private Geometries() {
    }

    public static Point point(double latitude, double longitude) {
        return FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    public static double latitude(Point point) {
        return point.getY();
    }

    public static double longitude(Point point) {
        return point.getX();
    }
}
