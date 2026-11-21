package com.gkcontas.geo.zone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Polygon;

import java.time.Instant;

/** A delivery zone. The polygon is validated before it gets here, and again by a CHECK constraint. */
@Entity
@Table(name = "zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Polygon area;

    @Column(name = "created_at")
    private Instant createdAt;

    public Zone(String name, Polygon area) {
        this.name = name;
        this.area = area;
        this.createdAt = Instant.now();
    }
}
