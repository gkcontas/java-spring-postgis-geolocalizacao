#!/usr/bin/env python3
"""Measurements for the README, in one session, against the packaged jar.

Usage: python3 scripts/measure.py [count]
Needs the application on :8080 and PostGIS from docker compose up.
"""
import json
import random
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

APP = "http://localhost:8080"
COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 200_000
SAMPLES = 60
WARMUP = 20

random.seed(43)
# Query points in the middle of the seeded area, away from its edges.
POINTS = [(-23.70 + random.random() * 0.25, -46.75 + random.random() * 0.30) for _ in range(20)]


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(APP + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def get(path, **params):
    status, body = call("GET", path + "?" + urllib.parse.urlencode(params))
    assert status == 200, (path, params, status, body)
    return body


def timed(fn):
    for i in range(WARMUP):
        fn(i)
    samples = []
    for i in range(SAMPLES):
        t = time.perf_counter()
        fn(i)
        samples.append((time.perf_counter() - t) * 1000)
    samples.sort()
    return {"p50": round(statistics.median(samples), 2), "p95": round(samples[int(len(samples) * 0.95) - 1], 2)}


def psql(sql):
    out = subprocess.run(["docker", "compose", "exec", "-T", "postgis", "psql", "-U", "geo", "-d", "geo", "-At", "-c", sql],
                         capture_output=True, text=True, check=True)
    return out.stdout.strip()


def section(title):
    print("\n== " + title, flush=True)


section(f"seed {COUNT} places")
status, seed = call("POST", f"/api/admin/seed?count={COUNT}")
print(seed)
print("table:", psql("SELECT pg_size_pretty(pg_relation_size('place'))"),
      "| gist geometry:", psql("SELECT pg_size_pretty(pg_relation_size('place_location_gist'))"),
      "| gist geography:", psql("SELECT pg_size_pretty(pg_relation_size('place_location_geog_gist'))"))

lat0, lng0 = POINTS[0]

section("plans")
for name, path, extra in [("radius 1 km", "/api/admin/compare/nearby", {"radius": 1000}),
                          ("nearest k=10", "/api/admin/compare/nearest", {"k": 10})]:
    strategies = ["INDEXED", "NO_INDEX"] + (["DISTANCE_FILTER"] if "nearby" in path else [])
    for strategy in strategies:
        r = get(path, latitude=lat0, longitude=lng0, strategy=strategy, explain="true", **extra)
        print(f"{name:14} {strategy:16} rows={r['count']:5} nodes={r['plan']['nodes']} indexes={r['plan']['indexes']}")

section("radius search: same rows, with and without index (p50/p95 ms over 20 points)")
for radius in (500, 1000, 5000):
    rows = [get("/api/admin/compare/nearby", latitude=la, longitude=ln, radius=radius)["count"] for la, ln in POINTS]
    line = f"radius {radius:5} m, rows avg {statistics.mean(rows):7.0f}:"
    for strategy in ("INDEXED", "NO_INDEX", "DISTANCE_FILTER"):
        stats = timed(lambda i: get("/api/admin/compare/nearby", latitude=POINTS[i % 20][0],
                                    longitude=POINTS[i % 20][1], radius=radius, strategy=strategy))
        line += f"  {strategy}={stats}"
    print(line, flush=True)

section("API /api/places/nearby (limit 50) and /api/places/nearest")
print("nearby 1 km  ", timed(lambda i: get("/api/places/nearby", latitude=POINTS[i % 20][0],
                                           longitude=POINTS[i % 20][1], radius=1000)))
print("nearest k=10 ", timed(lambda i: get("/api/places/nearest", latitude=POINTS[i % 20][0],
                                           longitude=POINTS[i % 20][1], k=10)))

section("nearest k=10: index-ordered scan vs sort of every row")
for strategy in ("INDEXED", "NO_INDEX"):
    print(strategy, timed(lambda i: get("/api/admin/compare/nearest", latitude=POINTS[i % 20][0],
                                        longitude=POINTS[i % 20][1], k=10, strategy=strategy)))

section("polygon: places covered by a zone")
status, zone = call("POST", "/api/zones", {"name": "Paulista", "area": {"type": "Polygon", "coordinates": [[
    [-46.67, -23.57], [-46.65, -23.57], [-46.65, -23.55], [-46.67, -23.55], [-46.67, -23.57]]]}})
assert status == 201, zone
zid = zone["id"]
print("zone area m2:", round(zone["areaSquareMeters"]), "perimeter m:", round(zone["perimeterMeters"]))
for strategy in ("INDEXED", "NO_INDEX"):
    r = get(f"/api/admin/compare/zones/{zid}/places", strategy=strategy, explain="true")
    print(strategy, "rows", r["count"], "nodes", r["plan"]["nodes"], "indexes", r["plan"]["indexes"],
          timed(lambda i: get(f"/api/admin/compare/zones/{zid}/places", strategy=strategy)))

section("area of the same 0.02 x 0.02 degree square, by latitude")
for name, lat in (("Equator", 0.0), ("Sao Paulo", -23.56), ("Oslo", 59.91)):
    lng = -46.67 if name == "Sao Paulo" else 10.73
    if name == "Sao Paulo":
        a = get(f"/api/zones/{zid}/area-comparison")
    else:
        status, z = call("POST", "/api/zones", {"name": name, "area": {"type": "Polygon", "coordinates": [[
            [lng, lat], [lng + 0.02, lat], [lng + 0.02, lat + 0.02], [lng, lat + 0.02], [lng, lat]]]}})
        assert status == 201, z
        a = get(f"/api/zones/{z['id']}/area-comparison")
    s = a["spheroid"]
    print(f"{name:10} deg2={a['squareDegrees']:.6f} spheroid={s:,.0f} sphere={a['sphere'] / s - 1:+.3%} "
          f"mercator={a['webMercator'] / s - 1:+.1%} utm({a['utmSrid']})={a['utm'] / s - 1:+.3%}")

section("degrees vs metres: rows found with a 'radius' of 0.009 degree vs 1000 m, by latitude")
for name, lat, lng in (("Sao Paulo", -23.56, -46.63),):
    deg = psql(f"SELECT count(*) FROM place WHERE ST_DWithin(location, ST_SetSRID(ST_MakePoint({lng}, {lat}), 4326), 0.009)")
    met = psql(f"SELECT count(*) FROM place WHERE ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint({lng}, {lat}), 4326)::geography, 1000)")
    print(name, "0.009 deg:", deg, "1000 m:", met)
