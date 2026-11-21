package com.gkcontas.geo.place;

/** A place found by a spatial query. {@code distanceMeters} is null when the query has no reference point. */
public record PlaceHit(long id, String name, String category, double latitude, double longitude,
                       Double distanceMeters) {
}
