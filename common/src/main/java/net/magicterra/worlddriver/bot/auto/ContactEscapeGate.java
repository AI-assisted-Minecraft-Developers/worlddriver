package net.magicterra.worlddriver.bot.auto;

import java.util.Set;

/**
 * Pure trigger gate for {@link ContactDamageEscape} (death #14, live 2026-07-20:
 * a goto grazed a desert cactus and ground the bot 11→0 HP in ~20 s). Split into
 * its own dependency-free file so the server-side gametest matrix can exercise
 * the trigger truth table with no client, same pattern as
 * {@link AntiSuffocateGate} / {@link DrowningFloatGate}.
 *
 * <p>Attribution rides {@code LocalPlayer.getLastDamageSource().getMsgId()} —
 * the server-authoritative damage mirror (gap#55/gap#69 precedent) — plus
 * {@code hurtTime > 0} for freshness: contact damage re-lands every ~10 ticks so
 * hurtTime stays hot for the whole episode, but decays to 0 within ≤10 ticks of
 * breaking contact (far tighter than the raw ~40-tick last-damager window, whose
 * stale tail is exactly what bit AntiSuffocate's fallback steps in final-review M1).
 */
public final class ContactEscapeGate {
    private ContactEscapeGate() {}

    /**
     * Damage-type msgIds dealt by touching/standing on a damaging BLOCK, where
     * stepping out of contact stops the bleed. Deliberately excludes:
     * "onFire" (the burn persists after leaving the fire block — movement does
     * not help, and shoving a burning bot mid-fight would fight the combat
     * chain), "freeze" (powder snow rescue is a climb-out, not a step-away),
     * "lava" (DrownEscape/walker lava handling owns fluids), and every
     * entity-attributed id (mob/arrow/player — those belong to the retreat
     * reflexes' entity attribution, gap#65/#68).
     */
    public static final Set<String> CONTACT_MSG_IDS =
            Set.of("cactus", "sweetBerryBush", "inFire", "hotFloor", "stalagmite");

    /** @param lastDamageMsgId {@code getLastDamageSource().getMsgId()}, null if none
     *  @param hurtTime        vanilla post-hit invulnerability countdown (10 → 0)
     *  @param enabled         {@code BotConfig.contactDamageEscape} */
    public static boolean shouldTrigger(String lastDamageMsgId, int hurtTime, boolean enabled) {
        return enabled && hurtTime > 0 && lastDamageMsgId != null
                && CONTACT_MSG_IDS.contains(lastDamageMsgId);
    }
}
