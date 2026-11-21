package com.gkcontas.geo.api;

import com.gkcontas.geo.place.PlaceQueries;
import com.gkcontas.geo.zone.AreaComparison;
import com.gkcontas.geo.zone.Zone;
import com.gkcontas.geo.zone.ZoneService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/zones")
public class ZoneController {

    private final ZoneService zones;
    private final PlaceQueries places;

    public ZoneController(ZoneService zones, PlaceQueries places) {
        this.zones = zones;
        this.places = places;
    }

    @PostMapping
    ResponseEntity<ZoneResponse> create(@Valid @RequestBody ZoneRequest request) {
        Zone zone = zones.create(request.name(), request.area());
        return ResponseEntity.created(URI.create("/api/zones/" + zone.getId()))
                .body(ZoneResponse.of(zone, zones.metrics(zone.getId())));
    }

    @GetMapping("/{id}")
    ZoneResponse get(@PathVariable long id) {
        Zone zone = zones.get(id);
        return ZoneResponse.of(zone, zones.metrics(id));
    }

    /** The places the zone covers, boundary included. */
    @GetMapping("/{id}/places")
    ZonePlacesResponse places(@PathVariable long id,
                              @RequestParam(defaultValue = "100")
                              @Min(value = 1, message = "must be between 1 and 1000")
                              @Max(value = 1000, message = "must be between 1 and 1000") int limit) {
        zones.get(id);
        return new ZonePlacesResponse(id, places.countInZone(id), places.inZone(id, limit));
    }

    /** The zone's area in square degrees, on the spheroid, on a sphere, in Web Mercator and in UTM. */
    @GetMapping("/{id}/area-comparison")
    AreaComparison areaComparison(@PathVariable long id) {
        return zones.compareAreas(id);
    }

    /** "Do you deliver here?": the zones that cover a point. */
    @GetMapping("/covering")
    List<ZoneService.ZoneSummary> covering(
            @RequestParam
            @DecimalMin(value = "-90", message = "must be between -90 and 90")
            @DecimalMax(value = "90", message = "must be between -90 and 90") double latitude,
            @RequestParam
            @DecimalMin(value = "-180", message = "must be between -180 and 180")
            @DecimalMax(value = "180", message = "must be between -180 and 180") double longitude) {
        return zones.covering(latitude, longitude);
    }
}
