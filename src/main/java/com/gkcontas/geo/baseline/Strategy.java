package com.gkcontas.geo.baseline;

/** How a comparison query runs. */
public enum Strategy {
    /** The production query, as the API runs it. */
    INDEXED,
    /** The same SQL with index and bitmap scans switched off for the transaction. */
    NO_INDEX,
    /** Radius only: ST_Distance(...) &lt;= r instead of ST_DWithin. Same rows, no index. */
    DISTANCE_FILTER
}
