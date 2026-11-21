package com.gkcontas.geo.api;

import com.gkcontas.geo.baseline.BaselineQueries;
import com.gkcontas.geo.baseline.Strategy;
import com.gkcontas.geo.place.PlaceHit;
import com.gkcontas.geo.seed.PlaceSeeder;
import com.gkcontas.geo.spatial.QueryPlan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Load and comparison endpoints for the README measurements. A demo
 * convenience: a real API would not let clients switch indexes off.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final PlaceSeeder seeder;
    private final BaselineQueries baseline;

    public AdminController(PlaceSeeder seeder, BaselineQueries baseline) {
        this.seeder = seeder;
        this.baseline = baseline;
    }

    /** {@code plan} only when asked for: EXPLAIN is a second round trip and would pollute the timing. */
    public record Comparison(Strategy strategy, int count, QueryPlan plan) {
    }

    @PostMapping("/seed")
    PlaceSeeder.Seeded seed(@RequestParam(defaultValue = "200000")
                            @Min(value = 1, message = "must be between 1 and 2000000")
                            @Max(value = 2_000_000, message = "must be between 1 and 2000000") int count) {
        return seeder.seed(count);
    }

    @GetMapping("/compare/nearby")
    Comparison nearby(@RequestParam double latitude, @RequestParam double longitude, @RequestParam double radius,
                      @RequestParam(defaultValue = "INDEXED") Strategy strategy,
                      @RequestParam(defaultValue = "false") boolean explain) {
        return compare(strategy, baseline.nearby(strategy, latitude, longitude, radius), explain);
    }

    @GetMapping("/compare/nearest")
    Comparison nearest(@RequestParam double latitude, @RequestParam double longitude,
                       @RequestParam(defaultValue = "10") int k,
                       @RequestParam(defaultValue = "INDEXED") Strategy strategy,
                       @RequestParam(defaultValue = "false") boolean explain) {
        return compare(strategy, baseline.nearest(latitude, longitude, k), explain);
    }

    @GetMapping("/compare/zones/{id}/places")
    Comparison inZone(@PathVariable long id, @RequestParam(defaultValue = "INDEXED") Strategy strategy,
                      @RequestParam(defaultValue = "false") boolean explain) {
        return compare(strategy, baseline.inZone(id), explain);
    }

    private Comparison compare(Strategy strategy, BaselineQueries.Query query, boolean explain) {
        List<PlaceHit> hits = baseline.run(strategy, query);
        return new Comparison(strategy, hits.size(), explain ? baseline.explain(strategy, query) : null);
    }
}
