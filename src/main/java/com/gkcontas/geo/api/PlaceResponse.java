package com.gkcontas.geo.api;

import com.gkcontas.geo.place.Place;

import java.time.Instant;

public record PlaceResponse(long id, String name, String category, double latitude, double longitude,
                            Instant createdAt) {

    static PlaceResponse of(Place place) {
        return new PlaceResponse(place.getId(), place.getName(), place.getCategory(), place.latitude(),
                place.longitude(), place.getCreatedAt());
    }
}
