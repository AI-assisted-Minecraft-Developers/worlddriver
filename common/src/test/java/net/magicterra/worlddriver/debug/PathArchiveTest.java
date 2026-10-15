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
}
