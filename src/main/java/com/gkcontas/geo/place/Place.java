package com.gkcontas.geo.place;

import com.gkcontas.geo.spatial.Geometries;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

/** A point of interest. Hibernate Spatial maps the JTS {@link Point} to {@code geometry(Point, 4326)}. */
@Entity
@Table(name = "place")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String category;

    private Point location;

    @Column(name = "created_at")
    private Instant createdAt;

    public Place(String name, String category, Point location) {
        this.name = name;
        this.category = category;
        this.location = location;
        this.createdAt = Instant.now();
    }

    public double latitude() {
        return Geometries.latitude(location);
    }

    public double longitude() {
        return Geometries.longitude(location);
    }
}
