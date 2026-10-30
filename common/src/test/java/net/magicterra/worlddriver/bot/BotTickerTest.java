package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.methods.shared.Responses.CancelReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Cancelled;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BotTickerTest {

    @AfterEach
    void clear() {
        BotTicker.clear();
    }

    @Test
    void aBotWhoseEntityIsGoneLeavesTheListAndItsActionEnds() {
        Bot gone = new EntitylessBot("test:gone");
        ActionHandle action = ((ActionRegistry.Admitted)
                        ActionRegistry.GLOBAL.admit(gone.id(), ActionHandle.Scope.SERVER, Optional.empty(), () -> "r"))
                .handle();
        BotTicker.register(gone);

        BotTicker.tickAll();

        assertFalse(BotTicker.isRegistered(gone));
        assertEquals(
                CancelReason.BOT_REMOVED,
                assertInstanceOf(Cancelled.class, action.snapshot()).reason());
    }

    /** A bot whose entity is already gone: every call but {@link #entity} and {@link #id} is unreachable. */
    private record EntitylessBot(String id) implements Bot {
        @Override
        public Locomotor.Status tick() {
            throw new AssertionError("ticked a bot with no entity");
        }

        @Override
        public boolean finished() {
            return false;
        }

        @Override
        public String kind() {
            return "test";
        }

        @Override
        public Body body() {
            return null;
        }

        @Override
        public LivingEntity entity() {
            return null;
        }

        @Override
        public BotState botState() {
            return null;
        }

        @Override
        public boolean busy() {
            return true;
        }

        @Override
        public Map<String, Object> lastProcessEnd() {
            return null;
        }

        @Override
        public void start(BotProcess process, ActionHandle action) {}

        @Override
        public boolean cancelAction(ActionHandle action, String reason) {
            return false;
        }

        @Override
        public String cancel(String process) {
            return null;
        }
    }
}
