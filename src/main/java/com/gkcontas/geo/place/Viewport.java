package com.gkcontas.geo.place;

import java.util.List;

public record Viewport(List<PlaceHit> places, boolean truncated) {
}
