package net.magicterra.worlddriver.api.game;

/**
 * A construction-time entry for content that ships only with the testmod: registrations that must
 * happen while the mod is being initialized, and any event subscriptions the test content wants.
 * Discovered through {@link java.util.ServiceLoader} by {@code WorldDriverCommon.installTestContent},
 * which both loader entries call from their constructor or initializer. An implementation that needs
 * a loader's own API lives in that loader's testmod (NeoForge's registers through
 * {@code @EventBusSubscriber} instead, since registration there needs the mod bus).
 *
 * <p>The published jar carries no implementation, so the discovery loop is empty there and
 * nothing happens — the same shape as StageWright's {@code SceneProvider}. Commands are not part
 * of this interface: an implementation subscribes to its loader's command registration event
 * itself and registers its own {@code worlddriver} literal, which Brigadier merges under the
 * driver's root.
 *
 * <p>It sits in the api rather than beside its implementations in {@code testcontent}, so the testmod
 * implements it through the api alone; and not in {@code testcontent} in any case, because on NeoForge the
 * main and testmod outputs are two JPMS modules, and a package present in both is a split package the module
 * layer refuses to build ("Modules generated_… and worlddriver export package … to module rhino"). Every
 * testmod package must therefore be one the main output does not have.
 */
public interface TestContent {
    /** Runs once, during mod construction, before any server or client exists. */
    void register();
}
