package net.magicterra.worlddriver.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ChartOutcome;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import org.junit.jupiter.api.Test;

/** The chart reports a trace's outcome by name, so the two enums must name the same endings. */
class ChartOutcomeTest {

    @Test
    void everyTraceOutcomeHasAChartOutcome() {
        Set<String> trace = new TreeSet<>(
                Arrays.stream(PathTrace.Outcome.values()).map(Enum::name).toList());
        trace.add(ChartOutcome.RUNNING.name());
        Set<String> chart = new TreeSet<>(
                Arrays.stream(ChartOutcome.values()).map(Enum::name).toList());
        assertEquals(trace, chart);
    }
}
