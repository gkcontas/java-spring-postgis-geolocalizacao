package com.gkcontas.geo.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Latitude and longitude by name: no array whose order the client has to guess. */
public record PlaceRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 200, message = "must have at most 200 characters")
        String name,
        @NotBlank(message = "must not be blank")
        @Size(max = 50, message = "must have at most 50 characters")
        String category,
        @NotNull(message = "is required")
        @DecimalMin(value = "-90", message = "must be between -90 and 90")
        @DecimalMax(value = "90", message = "must be between -90 and 90")
        Double latitude,
        @NotNull(message = "is required")
        @DecimalMin(value = "-180", message = "must be between -180 and 180")
        @DecimalMax(value = "180", message = "must be between -180 and 180")
        Double longitude) {
}
