package com.gkcontas.geo.api;

import com.gkcontas.geo.place.PlaceHit;

import java.util.List;

public record ZonePlacesResponse(long zoneId, long total, List<PlaceHit> places) {
}
