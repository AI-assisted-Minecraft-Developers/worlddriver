package net.magicterra.worlddriver.rpc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integral numbers reach the wire exactly.
 *
 * <p>Every Number went through {@code doubleValue()}, which has 53 bits of mantissa, so a
 * random 64-bit world seed in a path archive came back with its low bits changed.
 */
class JsonCodecNumberTest {

    @Test
    void aLongAbove2To53RoundTripsExactly() {
        long seed = -4172144997902289642L;
        long past53 = (1L << 53) + 1;
        Object back = JsonCodec.decode(JsonCodec.encode(Map.of("seed", seed, "n", past53, "max", Long.MAX_VALUE)));
        Map<?, ?> m = (Map<?, ?>) back;
        assertEquals(seed, m.get("seed"));
        assertEquals(past53, m.get("n"));
        assertEquals(Long.MAX_VALUE, m.get("max"));
    }

    @Test
    void everyIntegralTypeIsWrittenAsItsExactDigits() {
        assertEquals("9007199254740993", JsonCodec.encode(9007199254740993L));
        assertEquals("-128", JsonCodec.encode((byte) -128));
        assertEquals("32767", JsonCodec.encode((short) 32767));
        assertEquals("2147483647", JsonCodec.encode(Integer.MAX_VALUE));
        assertEquals("9007199254740993", JsonCodec.encode(new AtomicLong(9007199254740993L)));
        assertEquals("123456789012345678901234567890",
                JsonCodec.encode(new BigInteger("123456789012345678901234567890")));
        assertEquals("12345678901234567890", JsonCodec.encode(new BigDecimal("12345678901234567890.000")));
        assertEquals("0.1000000000000000000001", JsonCodec.encode(new BigDecimal("0.1000000000000000000001")));
    }

    @Test
    void floatingTypesKeepTheirExistingShape() {
        assertEquals("1.5", JsonCodec.encode(1.5));
        assertEquals("3", JsonCodec.encode(3.0));
        assertEquals("0.25", JsonCodec.encode(0.25f));
        assertEquals("null", JsonCodec.encode(Double.NaN));
        assertEquals("null", JsonCodec.encode(Float.POSITIVE_INFINITY));
    }
}
