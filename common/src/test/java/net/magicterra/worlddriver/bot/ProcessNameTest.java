package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.ProcessName;
import net.magicterra.worlddriver.api.schema.WireName;
import org.junit.jupiter.api.Test;

/**
 * {@code mc.bot.cancel}'s process names are spelled twice: by each process's {@code kind()} and each chain's
 * {@code name()} below the facade, which may not name the api, and by {@link ProcessName}. A process added without a
 * name, or a name left after its process went, fails here instead of being refused on the wire.
 */
class ProcessNameTest {

    private static final Path BOT = Path.of("src/main/java/net/magicterra/worlddriver/bot");
    private static final Pattern KIND = Pattern.compile("String kind\\(\\)\\s*\\{\\s*return \"([^\"]+)\"");
    private static final Pattern CHAIN_NAME =
            Pattern.compile("String name\\(\\)\\s*\\{\\s*return (?:\"([^\"]+)\"|NAME)[^}]*}");
    private static final Pattern NAME_CONSTANT = Pattern.compile("static final String NAME = \"([^\"]+)\"");

    @Test
    void theEnumIsEveryProcessKindAndChainName() throws IOException {
        Set<String> spelled = new TreeSet<>(Set.of("all"));
        int kinds = 0;
        try (Stream<Path> files = Files.walk(BOT.resolve("behavior"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = KIND.matcher(Files.readString(f));
                while (m.find()) {
                    spelled.add(m.group(1));
                    kinds++;
                }
            }
        }
        int chains = 0;
        try (Stream<Path> files = Files.list(BOT.resolve("arbitration"))) {
            for (Path f : files.filter(p -> p.toString().endsWith("Chain.java")).toList()) {
                String src = Files.readString(f);
                Matcher m = CHAIN_NAME.matcher(src);
                if (!m.find()) continue;
                if (m.group(1) != null) {
                    spelled.add(m.group(1));
                } else {
                    Matcher c = NAME_CONSTANT.matcher(src);
                    assertTrue(c.find(), f + " returns NAME but declares none this test can read");
                    spelled.add(c.group(1));
                }
                chains++;
            }
        }
        assertTrue(kinds >= 15 && chains >= 7, "the scan found only " + kinds + " kinds and " + chains + " chains");
        Set<String> api = new TreeSet<>(
                Arrays.stream(ProcessName.values()).map(WireName::wireName).toList());
        assertEquals(spelled, api);
    }
}
