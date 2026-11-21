package com.gkcontas.geo.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code area} is a GeoJSON Polygon: positions are [longitude, latitude]. */
public record ZoneRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 200, message = "must have at most 200 characters")
        String name,
        @NotNull(message = "is required")
        JsonNode area) {
}
