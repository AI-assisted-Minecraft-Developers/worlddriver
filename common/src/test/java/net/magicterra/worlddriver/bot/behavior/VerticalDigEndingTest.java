package net.magicterra.worlddriver.bot.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import net.magicterra.worlddriver.game.body.Body;
import org.junit.jupiter.api.Test;

/** Descend and escape share the escape slot, so they must leave it the same way when they end. */
class VerticalDigEndingTest {

    /** A body over no entity: both processes end on their first tick with "no player". */
    private static final Body BODY =
            (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> null);

    private static void assertEndedClean(BotState st) {
        BotState.ProcessSlot s = st.escape;
        assertFalse(s.active);
        assertNull(s.goal, "goal lingers: " + s.snapshot());
        assertNull(s.target);
        assertEquals(0, s.startedAtMs);
        assertEquals(0, s.pathLen);
        assertEquals("no player", s.lastError);
    }

    @Test
    void descendEndsLikeEscape() {
        BotState st = new BotState();
        DescendProcess p = new DescendProcess(10);
        p.attach(st);
        assertTrue(p.tick(BODY, null, st));
        assertEndedClean(st);
    }

    @Test
    void escapeEndsClean() {
        BotState st = new BotState();
        EscapeProcess p = new EscapeProcess(80);
        p.attach(st);
        assertTrue(p.tick(BODY, null, st));
        assertEndedClean(st);
    }
}
