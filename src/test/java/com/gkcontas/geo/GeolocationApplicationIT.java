package com.gkcontas.geo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GeolocationApplicationIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void postgisIsInstalled() {
        assertThat(jdbc.queryForObject("SELECT postgis_lib_version()", String.class)).startsWith("3.5");
    }
}
