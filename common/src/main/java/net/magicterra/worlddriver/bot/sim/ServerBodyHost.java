package net.magicterra.worlddriver.bot.sim;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.minecraft.world.entity.LivingEntity;

/**
 * A joined server player under a {@link ServerWorldDriver}, addressable as {@code player:<name>}.
 *
 * <p>The driver is dropped from {@link ServerAvatarManager} when it finishes, so {@link #start}
 * registers it again, the way every scene that reuses a driver does.
 */
public final class ServerBodyHost implements BodyHost {

    public static final String KIND = "player";

    private final String id;
    private final ServerWorldDriver driver;

    public ServerBodyHost(String name, ServerWorldDriver driver) {
        this.id = KIND + ":" + name;
        this.driver = driver;
    }

    public ServerWorldDriver driver() { return driver; }

    @Override public String id() { return id; }
    @Override public String kind() { return KIND; }
    @Override public ServerPlayerBody body() { return driver.avatar(); }
    @Override public LivingEntity entity() { return driver.fakePlayer(); }
    @Override public BotState botState() { return driver.botState(); }

    /** A driver that is off the tick list is not moving, whatever it last held. */
    @Override public boolean busy() {
        return driver.activeKind() != null && ServerAvatarManager.isRegistered(driver);
    }

    @Override public void start(BotProcess process) {
        driver.runProcess(process);
        ServerAvatarManager.register(driver);
    }

    @Override public String cancel(String process) {
        String kind = busy() ? driver.activeKind() : null;
        if (kind == null || !("all".equals(process) || kind.equals(process))) return null;
        driver.cancel("user-cancel");
        return kind;
    }
}
