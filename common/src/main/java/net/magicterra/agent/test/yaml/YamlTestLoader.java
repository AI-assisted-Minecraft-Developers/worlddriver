package net.magicterra.agent.test.yaml;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads YAML GameTest definitions from {@code data/agent_driver/gametests/}.
 *
 * <p>A jar cannot list a resource directory, so the file set is enumerated from
 * {@code index.txt} (one filename per line; blank lines and {@code #}-comments
 * ignored) — the same hardcoded-manifest convention the JS validation suite uses
 * in {@code AgentDriverCommon.runValidation()}. A YAML file is a top-level list
 * of test maps (a single bare map is tolerated as a one-test file).
 *
 * <p>Parsing uses snakeyaml's {@link SafeConstructor}, so only standard YAML
 * types (Map/List/String/Integer/Double/Boolean/null) are ever materialised —
 * never arbitrary Java object instantiation.
 */
public final class YamlTestLoader {
    private static final String DIR = "/data/agent_driver/gametests/";
    private static final String INDEX = DIR + "index.txt";

    private YamlTestLoader() {}

    /** Every spec across every file listed in {@code index.txt}. */
    public static List<YamlTestSpec> loadAll() {
        List<YamlTestSpec> specs = new ArrayList<>();
        for (String file : readIndex()) specs.addAll(loadFile(file));
        return specs;
    }

    /** Parse one classpath YAML file into specs. */
    public static List<YamlTestSpec> loadFile(String fileName) {
        try (InputStream in = YamlTestLoader.class.getResourceAsStream(DIR + fileName)) {
            if (in == null) throw new IllegalStateException("YAML test file not found: " + DIR + fileName);
            return parse(newYaml().load(in), fileName);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + fileName, e);
        }
    }

    /** Parse an inline YAML string (used by the {@code mc.test.yaml} route). */
    public static List<YamlTestSpec> parseString(String yaml, String label) {
        return parse(newYaml().load(yaml), label);
    }

    private static List<YamlTestSpec> parse(Object root, String label) {
        List<YamlTestSpec> out = new ArrayList<>();
        if (root instanceof List<?> list) {
            for (Object e : list) out.add(specFromEntry(e, label));
        } else if (root instanceof Map<?, ?>) {
            out.add(specFromEntry(root, label));
        } else {
            throw new IllegalArgumentException(label + ": top level must be a list of tests");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static YamlTestSpec specFromEntry(Object e, String label) {
        if (!(e instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(label + ": each test must be a map");
        }
        try {
            return YamlTestSpec.fromMap((Map<String, Object>) m);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(label + ": " + ex.getMessage(), ex);
        }
    }

    private static List<String> readIndex() {
        try (InputStream in = YamlTestLoader.class.getResourceAsStream(INDEX)) {
            if (in == null) return List.of();   // no YAML tests registered yet
            List<String> files = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String s = line.trim();
                    if (!s.isEmpty() && !s.startsWith("#")) files.add(s);
                }
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + INDEX, e);
        }
    }

    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }
}
