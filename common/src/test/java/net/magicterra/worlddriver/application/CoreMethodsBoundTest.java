package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.api.methods.CoreMethods;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import org.junit.jupiter.api.Test;

/**
 * The generator documents {@link CoreMethods}; the application serves its own batch. The two lists must be the same
 * specs, or a method ships declarations with no handler, or a handler with none.
 */
class CoreMethodsBoundTest {

    @Test
    void everyDescribedCoreMethodIsServedAndNothingElseIs() {
        ApiRegistry.Snapshot snap = new DriverApplication().registry().snapshot();
        List<String> served = new ArrayList<>();
        snap.methods().forEach((name, m) -> {
            // ownerOf names the batch, "<owner>/<id>".
            if (snap.ownerOf().get(name).startsWith(ApiRegistry.CORE_OWNER + "/")) served.add(name);
        });
        List<String> described = new ArrayList<>();
        for (MethodSpec<?, ?> s : CoreMethods.all()) {
            described.add(s.name());
            assertSame(s, snap.methods().get(s.name()).spec(), s.name() + " is served with another spec object");
        }
        assertEquals(
                described.stream().sorted().toList(), served.stream().sorted().toList());
    }
}
