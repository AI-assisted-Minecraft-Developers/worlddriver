package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.contract.RejectedNumber;
import net.magicterra.worlddriver.protocol.JsonCodec;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decode half: exact numbers, the number budget, and the input that is not JSON. */
class JsonCodecStrictTest {

    @Test
    void aDecimalKeepsEveryDigit() {
        Object v = JsonCodec.decode("1.0000000000000000001");
        assertEquals(new BigDecimal("1.0000000000000000001"), v);
    }

    @Test
    void integersAreLongUntilTheyAreNot() {
        assertEquals(12L, JsonCodec.decode("12"));
        assertEquals(new BigInteger("92233720368547758080"), JsonCodec.decode("92233720368547758080"));
        assertInstanceOf(BigDecimal.class, JsonCodec.decode("1.0"));
        assertInstanceOf(BigDecimal.class, JsonCodec.decode("1e2"));
    }

    @Test
    void aRepeatedKeyIsNotJson() {
        assertThrows(JsonCodec.JsonSyntaxException.class, () -> JsonCodec.decode("{\"a\":1,\"a\":2}"));
    }

    @Test
    void numbersOutsideTheGrammarAreRefused() {
        for (String bad : List.of("01", "1.", ".5", "+1", "1e", "1e+", "--1", "NaN", "Infinity", "-Infinity", "0x10")) {
            assertThrows(JsonCodec.JsonSyntaxException.class, () -> JsonCodec.decode(bad), bad);
        }
    }

    @Test
    void anOverBudgetNumberIsKeptAsARejectionNotAValue() {
        Object huge = ((Map<?, ?>) JsonCodec.decode("{\"id\":7,\"n\":1e401}")).get("n");
        assertInstanceOf(RejectedNumber.class, huge);
        assertEquals(7L, ((Map<?, ?>) JsonCodec.decode("{\"id\":7,\"n\":1e401}")).get("id"));
        assertInstanceOf(BigDecimal.class, JsonCodec.decode("1e400"));
        assertInstanceOf(BigDecimal.class, JsonCodec.decode("1e-0400"));
        Object long101 = JsonCodec.decode("1" + "0".repeat(100));
        assertInstanceOf(RejectedNumber.class, long101);
        assertInstanceOf(BigInteger.class, JsonCodec.decode("1" + "0".repeat(99)));
    }

    @Test
    void controlCharactersMustBeEscaped() {
        assertThrows(JsonCodec.JsonSyntaxException.class, () -> JsonCodec.decode("\"a\nb\""));
        assertEquals("a\nb", JsonCodec.decode("\"a\\nb\""));
    }

    @Test
    void anOverBudgetNumberIsReportedAsSuch() {
        RejectedNumber r = (RejectedNumber) JsonCodec.decode("1e9999");
        assertTrue(r.reason().contains("exponent"));
    }
}
