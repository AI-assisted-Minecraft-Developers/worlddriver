package net.magicterra.worlddriver.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PathArchiveTest {

    /** A plan archive's ticks carry no deviation; that NaN has to survive a JSON round trip. */
    @Test
    void anUnmeasuredDeviationRoundTripsThroughJson() {
        PathArchive demo = PathArchive.demo();
        assertTrue(Double.isNaN(demo.trajectory().get(0).deviation()));

        String json = demo.toJson();
        assertTrue(json.contains("\"deviation\":null"), json);

        PathArchive back = PathArchive.fromJson(json);
        assertEquals(demo.trajectory().size(), back.trajectory().size());
        assertTrue(Double.isNaN(back.trajectory().get(0).deviation()));
        assertEquals(json, back.toJson());
    }

    /** Every top-level collection keeps its size through a JSON round trip, and an envelope cell keeps its
     *  block-state SNBT and block-entity NBT. */
    @Test
    void everyCollectionAndTheEnvelopeStateRoundTrip() {
        PathArchive a = PathArchive.demo();
        PathArchive b = PathArchive.fromJson(a.toJson());
        assertEquals(a.header().seed(), b.header().seed(), "seed");
        assertEquals(a.segments().size(), b.segments().size(), "segments");
        assertEquals(a.trajectory().size(), b.trajectory().size(), "trajectory");
        assertEquals(a.segments().get(0).nodes().size(), b.segments().get(0).nodes().size(), "nodes");
        assertEquals(a.envelope().size(), b.envelope().size(), "envelope");
        assertEquals(a.envelope().get(0).state(), b.envelope().get(0).state(), "envelope state");
        assertEquals(a.envelope().get(0).nbt(), b.envelope().get(0).nbt(), "envelope nbt");
    }
}
