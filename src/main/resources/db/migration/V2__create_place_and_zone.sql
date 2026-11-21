-- Coordinates are stored as geometry in WGS 84 (SRID 4326), longitude first.
-- geometry, not geography: geography lacks ST_Contains, envelope (&&) searches
-- in degrees and ST_Transform. Distances in metres come from casting to
-- geography, and that cast gets its own index below.
CREATE TABLE place (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200)           NOT NULL,
    category   VARCHAR(50)            NOT NULL,
    location   geometry(Point, 4326)  NOT NULL,
    created_at TIMESTAMPTZ            NOT NULL DEFAULT now(),
    CONSTRAINT place_location_in_range
        CHECK (ST_X(location) BETWEEN -180 AND 180 AND ST_Y(location) BETWEEN -90 AND 90)
);

-- Bounding-box searches (&&), polygon searches (ST_Covers) and KNN in degrees.
CREATE INDEX place_location_gist ON place USING gist (location);

-- Radius and nearest-neighbour in metres. A predicate on location::geography
-- cannot use the index above: the planner only matches an index whose
-- expression is the one in the query.
CREATE INDEX place_location_geog_gist ON place USING gist ((location::geography));

CREATE INDEX place_category_idx ON place (category);

CREATE TABLE zone (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200)             NOT NULL,
    area       geometry(Polygon, 4326)  NOT NULL,
    created_at TIMESTAMPTZ              NOT NULL DEFAULT now(),
    CONSTRAINT zone_name_key UNIQUE (name),
    -- Last line of defence. The API validates with JTS first, to answer with
    -- the reason; this catches anything that reaches the table another way.
    CONSTRAINT zone_area_valid CHECK (ST_IsValid(area))
);

CREATE INDEX zone_area_gist ON zone USING gist (area);
