package com.gkcontas.geo;

import com.gkcontas.geo.place.Place;
import com.gkcontas.geo.place.PlaceService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostGIS container for the whole suite, started from a static
 * initialiser. Not {@code @Testcontainers}: Spring caches application
 * contexts across test classes, and a container tied to a class is stopped
 * while a cached context still points at it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGIS.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlaceService places;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE place, zone RESTART IDENTITY");
    }

    protected Place place(String name, double latitude, double longitude) {
        return places.create(name, "restaurant", latitude, longitude);
    }

    /** Metres to degrees of latitude: about 111.1 km per degree everywhere. */
    protected static double north(double meters) {
        return meters / 111_132.0;
    }

    /** Metres to degrees of longitude at a latitude: the degree shrinks with cos(latitude). */
    protected static double east(double meters, double latitude) {
        return meters / (111_320.0 * Math.cos(Math.toRadians(latitude)));
    }
}
