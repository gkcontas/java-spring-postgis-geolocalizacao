package com.gkcontas.geo.api;

import com.gkcontas.geo.place.PlaceHit;
import com.gkcontas.geo.place.PlaceQueries;
import com.gkcontas.geo.place.PlaceService;
import com.gkcontas.geo.place.Viewport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService places;
    private final PlaceQueries queries;

    public PlaceController(PlaceService places, PlaceQueries queries) {
        this.places = places;
        this.queries = queries;
    }

    @PostMapping
    ResponseEntity<PlaceResponse> create(@Valid @RequestBody PlaceRequest request) {
        PlaceResponse body = PlaceResponse.of(
                places.create(request.name(), request.category(), request.latitude(), request.longitude()));
        return ResponseEntity.created(URI.create("/api/places/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    PlaceResponse get(@PathVariable long id) {
        return PlaceResponse.of(places.get(id));
    }

    /** Everything within {@code radius} metres, nearest first. */
    @GetMapping("/nearby")
    List<PlaceHit> nearby(
            @RequestParam
            @DecimalMin(value = "-90", message = "must be between -90 and 90")
            @DecimalMax(value = "90", message = "must be between -90 and 90") double latitude,
            @RequestParam
            @DecimalMin(value = "-180", message = "must be between -180 and 180")
            @DecimalMax(value = "180", message = "must be between -180 and 180") double longitude,
            @RequestParam
            @DecimalMin(value = "1", message = "must be between 1 and 50000 metres")
            @DecimalMax(value = "50000", message = "must be between 1 and 50000 metres") double radius,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "50")
            @Min(value = 1, message = "must be between 1 and 500")
            @Max(value = 500, message = "must be between 1 and 500") int limit) {
        return queries.nearby(latitude, longitude, radius, category, limit);
    }

    /** The k nearest, optionally no farther than {@code maxDistance} metres. */
    @GetMapping("/nearest")
    List<PlaceHit> nearest(
            @RequestParam
            @DecimalMin(value = "-90", message = "must be between -90 and 90")
            @DecimalMax(value = "90", message = "must be between -90 and 90") double latitude,
            @RequestParam
            @DecimalMin(value = "-180", message = "must be between -180 and 180")
            @DecimalMax(value = "180", message = "must be between -180 and 180") double longitude,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "must be between 1 and 100")
            @Max(value = 100, message = "must be between 1 and 100") int k,
            @RequestParam(required = false)
            @DecimalMin(value = "1", message = "must be at least 1 metre") Double maxDistance) {
        return queries.nearest(latitude, longitude, k, maxDistance);
    }

    /** The places inside a map viewport. {@code west > east} means the viewport crosses the antimeridian. */
    @GetMapping("/in-viewport")
    Viewport viewport(
            @RequestParam
            @DecimalMin(value = "-180", message = "must be between -180 and 180")
            @DecimalMax(value = "180", message = "must be between -180 and 180") double west,
            @RequestParam
            @DecimalMin(value = "-90", message = "must be between -90 and 90")
            @DecimalMax(value = "90", message = "must be between -90 and 90") double south,
            @RequestParam
            @DecimalMin(value = "-180", message = "must be between -180 and 180")
            @DecimalMax(value = "180", message = "must be between -180 and 180") double east,
            @RequestParam
            @DecimalMin(value = "-90", message = "must be between -90 and 90")
            @DecimalMax(value = "90", message = "must be between -90 and 90") double north,
            @RequestParam(defaultValue = "200")
            @Min(value = 1, message = "must be between 1 and 1000")
            @Max(value = 1000, message = "must be between 1 and 1000") int limit) {
        if (south > north) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "south must not be greater than north");
        }
        return queries.viewport(west, south, east, north, limit);
    }
}
