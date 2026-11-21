package com.gkcontas.geo.zone;

public class DuplicateZoneException extends RuntimeException {

    public DuplicateZoneException(String name) {
        super("zone " + name + " already exists");
    }
}
