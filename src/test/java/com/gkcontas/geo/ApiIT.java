package com.gkcontas.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return http.postForEntity(path, body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return http.getForEntity(path, JsonNode.class);
    }

    @Test
    void createsAndReadsAPlace() {
        ResponseEntity<JsonNode> created = post("/api/places",
                Map.of("name", "Masp", "category", "museum", "latitude", -23.5614, "longitude", -46.6559));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getHeaders().getLocation()).hasPath("/api/places/" + id);

        JsonNode read = get("/api/places/" + id).getBody();
        assertThat(read.get("latitude").asDouble()).isEqualTo(-23.5614);
        assertThat(read.get("longitude").asDouble()).isEqualTo(-46.6559);
    }

    @Test
    void validatesAPlace() {
        ResponseEntity<JsonNode> response = post("/api/places",
                Map.of("name", "", "category", "museum", "latitude", -123.5, "longitude", -46.6));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("details")).extracting(JsonNode::asText)
                .containsExactly("latitude: must be between -90 and 90", "name: must not be blank");
    }

    @Test
    void unknownPlaceIs404() {
        ResponseEntity<JsonNode> response = get("/api/places/999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error").asText()).isEqualTo("place 999 not found");
    }

    @Test
    void searchesNearbyAndNearest() {
        place("near", -23.5505 + north(200), -46.6333);
        place("far", -23.5505 + north(2000), -46.6333);

        JsonNode nearby = get("/api/places/nearby?latitude=-23.5505&longitude=-46.6333&radius=1000").getBody();
        JsonNode nearest = get("/api/places/nearest?latitude=-23.5505&longitude=-46.6333&k=2").getBody();

        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).get("name").asText()).isEqualTo("near");
        assertThat(nearby.get(0).get("distanceMeters").asDouble()).isBetween(195.0, 205.0);
        assertThat(nearest).extracting(n -> n.get("name").asText()).containsExactly("near", "far");
    }

    @Test
    void validatesSearchParameters() {
        ResponseEntity<JsonNode> radius = get("/api/places/nearby?latitude=-23.5&longitude=-46.6&radius=90000");
        ResponseEntity<JsonNode> missing = get("/api/places/nearby?latitude=-23.5&longitude=-46.6");
        ResponseEntity<JsonNode> viewport = get("/api/places/in-viewport?west=-47&south=-23&east=-46&north=-24");

        assertThat(radius.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(radius.getBody().get("details").get(0).asText()).isEqualTo("radius: must be between 1 and 50000 metres");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(viewport.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(viewport.getBody().get("error").asText()).isEqualTo("south must not be greater than north");
    }

    @Test
    void createsAZoneAndAnswersInGeoJson() throws Exception {
        place("inside", -23.56, -46.66);
        ResponseEntity<JsonNode> created = post("/api/zones",
                Map.of("name", "Paulista", "area", json.readTree(ZonesIT.PAULISTA)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode zone = created.getBody();
        assertThat(zone.get("area").get("type").asText()).isEqualTo("Polygon");
        // [longitude, latitude], as it came in.
        assertThat(zone.get("area").get("coordinates").get(0).get(0).get(0).asDouble()).isEqualTo(-46.67);
        assertThat(zone.get("areaSquareMeters").asDouble()).isBetween(4.4e6, 4.6e6);

        long id = zone.get("id").asLong();
        JsonNode inside = get("/api/zones/" + id + "/places").getBody();
        assertThat(inside.get("total").asLong()).isOne();
        assertThat(inside.get("places").get(0).get("distanceMeters").isNull()).isTrue();
        JsonNode covering = get("/api/zones/covering?latitude=-23.56&longitude=-46.66").getBody();
        assertThat(covering.get(0).get("name").asText()).isEqualTo("Paulista");
        JsonNode areas = get("/api/zones/" + id + "/area-comparison").getBody();
        assertThat(areas.get("utmSrid").asInt()).isEqualTo(32723);
    }

    @Test
    void refusesAnInvalidZoneWithTheReason() throws Exception {
        ResponseEntity<JsonNode> bowTie = post("/api/zones", Map.of("name", "bow tie", "area",
                json.readTree("{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,1],[1,0],[0,1],[0,0]]]}")));
        ResponseEntity<JsonNode> noArea = post("/api/zones", Map.of("name", "nothing"));

        assertThat(bowTie.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bowTie.getBody().get("error").asText()).startsWith("invalid polygon: Self-intersection");
        assertThat(noArea.getBody().get("details").get(0).asText()).isEqualTo("area: is required");
    }

    @Test
    void duplicateZoneIs409AndUnknownZoneIs404() throws Exception {
        Map<String, Object> body = Map.of("name", "Paulista", "area", json.readTree(ZonesIT.PAULISTA));
        post("/api/zones", body);

        assertThat(post("/api/zones", body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/api/zones/999/places").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedJsonIs400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = http.postForEntity("/api/places", new HttpEntity<>("{\"name\":", headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).isEqualTo("malformed request");
    }

    @Test
    void comparisonEndpointReportsCountAndPlan() {
        post("/api/admin/seed?count=2000", null);

        JsonNode indexed = get("/api/admin/compare/nearest?latitude=-23.55&longitude=-46.63&k=5&explain=true").getBody();
        JsonNode unindexed = get("/api/admin/compare/nearest?latitude=-23.55&longitude=-46.63&k=5&strategy=NO_INDEX&explain=true")
                .getBody();

        assertThat(indexed.get("count").asInt()).isEqualTo(5);
        assertThat(indexed.get("plan").get("indexes").get(0).asText()).isEqualTo("place_location_geog_gist");
        assertThat(unindexed.get("plan").get("indexes")).isEmpty();
        assertThat(get("/api/admin/compare/nearest?latitude=-23.55&longitude=-46.63").getBody().get("plan").isNull())
                .isTrue();
    }
}
