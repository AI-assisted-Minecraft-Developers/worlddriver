package net.magicterra.agent.mcp;

import net.magicterra.agent.api.AgentApi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "hidden" means for a tool, as an assertion rather than a comment.
 *
 * <p>{@code mc.test.yaml}'s description used to claim it was "reachable over RPC
 * only". It never was: {@code McpServer}'s {@code tools/call} validates the name
 * against {@code AgentApi.methods()} — every registered route — not against the
 * advertised list, so a hidden verb is callable on MCP too. Nothing enforced the
 * claim and nothing contradicted it either; it was prose next to code that did
 * something else, which is how the {@code logging/setLevel} doc drifted as well.
 *
 * <p>The behavior is the intended one — hiding saves prompt tokens (hard rule #6),
 * it is not an access boundary, and adding one would make the transports disagree
 * about what a method does (hard rule #1). So this pins the two halves that matter:
 * hidden verbs stay out of the advertised list, and stay in the declared/routable
 * set. If either flips, this fails instead of a comment quietly becoming false.
 */
class ToolCatalogHiddenTest {

    private static final String HIDDEN = "mc.test.yaml";

    @Test
    void hiddenToolIsNotAdvertisedInToolsList() {
        List<Map<String, Object>> advertised = ToolCatalog.tools();
        assertFalse(advertised.stream().anyMatch(t -> HIDDEN.equals(t.get("name"))),
                HIDDEN + " must stay out of tools/list — every listed tool's schema is "
                + "sent to every LLM client on every turn");
        assertTrue(advertised.size() > 5, "sanity: the catalog should not be empty");
    }

    @Test
    void hiddenToolIsStillDeclaredAndRoutable() {
        Set<String> declared = ToolCatalog.declaredMethodNames();
        assertTrue(declared.contains(HIDDEN),
                HIDDEN + " must stay DECLARED — the boot invariant requires every route "
                + "to carry a ToolSchema, so dropping it here would fail startup");
        assertTrue(new AgentApi().methods().contains(HIDDEN),
                HIDDEN + " must stay routable: this is what tools/call checks against, "
                + "which is why hiding does not make it uncallable");
    }

    @Test
    void everyAdvertisedToolIsAlsoDeclared() {
        Set<String> declared = ToolCatalog.declaredMethodNames();
        for (Map<String, Object> t : ToolCatalog.tools()) {
            assertTrue(declared.contains(String.valueOf(t.get("name"))),
                    "advertised but not declared: " + t.get("name"));
        }
    }
}
