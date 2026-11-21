package com.gkcontas.geo.place;

import com.gkcontas.geo.spatial.Geometries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceService {

    private final PlaceRepository places;

    public PlaceService(PlaceRepository places) {
        this.places = places;
    }

    @Transactional
    public Place create(String name, String category, double latitude, double longitude) {
        return places.save(new Place(name, category, Geometries.point(latitude, longitude)));
    }

    @Transactional(readOnly = true)
    public Place get(long id) {
        return places.findById(id).orElseThrow(() -> new NotFoundException("place " + id + " not found"));
    }
}
