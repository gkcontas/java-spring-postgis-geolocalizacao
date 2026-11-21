package com.gkcontas.geo.zone;

/**
 * The same polygon's area computed five ways.
 *
 * @param squareDegrees     ST_Area on geometry 4326: degrees squared, meaningless as a surface
 * @param spheroid          geography on the WGS 84 spheroid: the reference
 * @param sphere            geography on a sphere: cheaper, a fraction of a percent off
 * @param webMercator       projected to EPSG:3857, the web map projection: inflated by 1/cos²(latitude)
 * @param utm               projected to the zone's own UTM zone: conformal, close to the spheroid locally
 * @param utmSrid           which UTM zone (326xx north, 327xx south)
 */
public record AreaComparison(double squareDegrees, double spheroid, double sphere, double webMercator, double utm,
                             int utmSrid) {
}
