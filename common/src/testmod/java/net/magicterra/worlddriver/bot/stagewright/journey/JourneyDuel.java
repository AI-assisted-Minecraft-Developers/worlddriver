package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.Optional;

import net.magicterra.worlddriver.api.methods.interact.InteractApi;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The two fights the End rungs drive tick by tick themselves, because no verb fights this way: stand still and
 * swing at one thing only when it is in reach ({@link SwingAt}, the crystals), and hold the arena's centre and
 * hit the dragon whenever a part of it comes close, drawing the bow while it circles ({@link DuelTheDragon}).
 *
 * <p>Each is a {@link Step} over the bot's player and its {@link Arms}, so one fight runs on either bot, its
 * arms the hand verbs and {@code mc.test.input.press} ({@link #arms(JourneyRig)}).
 */
final class JourneyDuel {
    private JourneyDuel() {}

    private static final int SWING_EVERY = JourneyEndRungs.SWING_EVERY;
    private static final int DUEL_OUT_OF_REACH_TICKS = JourneyEndRungs.DUEL_OUT_OF_REACH_TICKS;

    /** What a fight does with the bot's hands and feet. */
    interface Arms {
        /** Swing at {@code target}; why the swing was declined, or null when it went out. */
        String attack(Entity target);

        /** Press ({@code down}) or let go of the use key, leaving it so. */
        void useKey(boolean down);

        /** Let go of movement, jump and dig: a fight stands still. */
        void standStill();
    }

    /** One tick of a fight over the bot's player and arms (null when it has none); true when the fight is over. */
    interface Step {
        boolean tick(Player p, Arms arms);
    }

    /**
     * The run's bot's arms: {@code mc.bot.attackEntity} for a swing — which refuses out of reach and declines a
     * swing the footing guard would, as the body's own swing did, and also turns the bot toward what it hit — and
     * {@code mc.test.input.press} for the use key and for standing still.
     *
     * <p>self answers on the client thread, after the tick that asked: its swing reports no refusal to the step,
     * and the refusal, if any, lands as evidence under {@code duel.attack} instead.
     *
     * <p><b>A part of the dragon cannot be named.</b> The verb resolves an entity id in the level, and a dragon's
     * parts are not level entities, so a swing at a part goes to the dragon itself — which vanilla takes as a
     * hit on its body, a quarter of a head hit. {@link DuelTheDragon}'s {@code headHits} counts swings AIMED at
     * the head; on a roster bot none of them lands as one.
     */
    static Arms arms(JourneyRig rig) {
        return new Arms() {
            @Override public String attack(Entity target) {
                Entity named = target instanceof EnderDragonPart part ? part.parentMob : target;
                var answer = InteractApi.of(Calls.api()).attackEntity(
                        new InteractContracts.AttackParams(rig.bot(), named.getId())).toCompletableFuture();
                if (!answer.isDone()) { rig.recordWhenLanded("duel.attack", answer); return null; }
                return Calls.now(answer) instanceof Responses.Refused<?> no ? no.message() : null;
            }

            @Override public void useKey(boolean down) { rig.holdUseKey(down); }

            @Override public void standStill() { rig.press(k -> k.move(0, 0).jump(false).attack(false)); }
        };
    }

    /**
     * Stand still and hit one thing, at vanilla's own tempo, only when it is genuinely in reach.
     *
     * <p>The reach test is the reason this exists rather than a bare {@code attackEntity} call.
     * {@code Player.attack} checks no distance, so a script could shatter a crystal forty blocks
     * overhead; gating on {@link #MELEE_REACH} is what makes the climb above it load-bearing. The
     * twenty-tick cadence is {@code LivingEntity.invulnerableTime} — swinging faster does not do more
     * damage, it does less, and it also charges the attack-strength meter for free.
     */
    static final class SwingAt implements Step {

        private final Entity target;
        private final int maxTicks;
        private final double reach;
        private int elapsed;
        private int sinceSwing = SWING_EVERY;
        private int swings;
        private int refused;
        private String refusal;
        private double closest = Double.MAX_VALUE;

        SwingAt(Entity target, int maxTicks, double reach) {
            this.target = target;
            this.maxTicks = maxTicks;
            this.reach = reach;
        }

        static final String KIND = "journeySwingAt";

        @Override
        public boolean tick(Player p, Arms arms) {
            if (arms != null) arms.standStill();
            if (p == null || arms == null || !target.isAlive()) return true;
            double d = p.distanceTo(target);
            if (d < closest) closest = d;
            sinceSwing++;
            if (d <= reach && sinceSwing >= SWING_EVERY) {
                String why = arms.attack(target);
                sinceSwing = 0;
                // A swing the driver DECLINED is not a swing. Rung 20 broke because a crystal took
                // the cage lid out from under the bot, and BlastFooting now refuses that hit — so
                // this loop can legitimately run its whole budget without the sword ever moving,
                // and "60 swings, crystal still alive" would describe that as a damage problem. Count what
                // happened, and carry the reason out: a refusal nobody prints is the silent failure
                // this rung has already been bitten by.
                if (why != null) { refused++; if (refusal == null) refusal = why; }
                else swings++;
            }
            return ++elapsed >= maxTicks;
        }

        int swings() { return swings; }

        int refused() { return refused; }

        /** Why the driver declined the first refused swing, or {@code null} if it never declined. */
        String refusal() { return refusal; }

        double closest() { return closest == Double.MAX_VALUE ? -1 : closest; }
    }

    /**
     * Hold the middle of the arena and hit the dragon whenever a part of it comes within reach.
     *
     * <p>Aims at the HEAD by preference and settles for whatever else is close, because the head
     * takes a hit undivided and every other part takes a quarter of it plus one — a loop that is
     * only ever clipping a wing is winning four times slower than its swing count suggests, and
     * {@code duel.swings} beside {@code headHits} is what says which happened.
     *
     * <p>It does not chase. A flying dragon cannot be caught by a walking player, and the fight
     * vanilla designs is one of waiting: the boss perches on the fountain, lowers its head, and that is the
     * window. Steering during that window is worse than standing still, which is why every input is
     * released on every tick — the same reason a rig hold cancels the bot's task.
     */
    static final class DuelTheDragon implements Step {

        private final int maxTicks;
        private final double reach;
        private int elapsed;
        private int sinceSwing = SWING_EVERY;
        private int swings;
        private int headHits;
        private double closest = Double.MAX_VALUE;
        private int outOfReach;
        private int arrows;
        private int ammoBefore = -1;
        private int drawTicks;
        private boolean bowBroken;
        private String bowError;
        private int bowTicks;   // ticks the ranged branch actually ran
        private int arrowsAtLastReach;
        private boolean gaveUp;
        /** Why this fight stopped. Four exits produce the identical outside view — a live dragon and
         *  a bot standing still — and guessing between them has already cost a round. */
        private String why = "still fighting";
        private String closestPart = "none";

        DuelTheDragon(int maxTicks, double reach) {
            this.maxTicks = maxTicks;
            this.reach = reach;
        }

        static final String KIND = "journeyDragonDuel";

        @Override
        public boolean tick(Player p, Arms arms) {
            if (arms != null) arms.standStill();
            if (p == null || arms == null) {
                why = "the bot's player entity is gone (no player, or no hands)"; return true;
            }
            EnderDragon dragon = JourneyEndRungs.nearestDragon(p.level(), p.position());
            if (dragon != null && dragon.isDeadOrDying()) { why = "the dragon is dying"; return true; }
            if (dragon == null) {
                // "Not in the search box" does not mean dead — this file's own death criterion says
                // exactly that, and the fight loop has to apply it too: a dragon that merely flew
                // past the search box would otherwise end the duel as if it were finished. Measured:
                // 5 swings, 5 of them on the head, then the dragon crossed to -66,87,26 and the fight
                // stopped there with the bot standing on the fountain doing nothing wrong. A circling
                // dragon leaving and returning is the NORMAL shape of this fight; only death or the
                // budget ends it.
                if (++outOfReach >= DUEL_OUT_OF_REACH_TICKS) {
                    gaveUp = true;
                    why = "gave up: no dragon in the search box for " + outOfReach + " consecutive ticks";
                    return true;
                }
                if (++elapsed >= maxTicks) { why = "budget exhausted (" + maxTicks + " ticks)"; return true; }
                return false;
            }

            Entity head = null;
            Entity nearest = null;
            String nearestName = "none";
            float headAway = Float.MAX_VALUE;
            float nearestAway = Float.MAX_VALUE;
            for (var part : dragon.getSubEntities()) {
                float d = p.distanceTo(part);
                if ("head".equals(part.name)) { head = part; headAway = d; }
                if (d < nearestAway) { nearestAway = d; nearest = part; nearestName = part.name; }
            }
            if (nearestAway < closest) { closest = nearestAway; closestPart = nearestName; }

            sinceSwing++;
            Entity aim = head != null && headAway <= reach ? head
                    : nearestAway <= reach ? nearest : null;
            if (aim != null && sinceSwing >= SWING_EVERY) {
                if (p.isUsingItem()) arms.useKey(false);   // drop the draw, this is melee now
                holdSword(p);
                arms.attack(aim);
                sinceSwing = 0;
                swings++;
                if (aim == head) headHits++;
            }
            // Out of melee reach is the NORMAL state of this fight, not a lull: a dragon only
            // brings its head down when it perches, and hits on any other part are quartered by
            // vanilla (measured: 1 body hit took 200.0 -> 198.8). So the circling phase is where a
            // bow earns the fight, and the engine has had the draw all along — CombatProcess.
            // rangedTick draws for BOW_FULL_DRAW ticks and releases on the up-edge. This is the
            // same mechanism, aimed at the head rather than at whatever part is nearest: an arrow
            // into the dragon's body is worth a quarter of one into the head, and the dragon presents its
            // body far more often.
            // A process that throws dies silently: the driver marks it finished and the rig's settle
            // returns as if it had completed, so the evidence row still reads "still fighting" while the
            // fight has in fact stopped. That is exactly what 14594 ticks of a 200000-tick budget
            // looked like. Name it instead of letting it read as a healthy fight that ran out.
            try {
            if (aim == null && head != null && holdBow(p)) {
                aimAtPart(p, head, headAway * 0.12);      // lead high for arrow drop
                // Vanilla's own draw, on vanilla's own counter. getTicksUsingItem() is not frozen for
                // this bot: the server-side player runs LivingEntity.tick, whose updatingUsingItem()
                // advances the timer exactly as it does for a real player. Counting ticks and calling
                // releaseUsing by hand would route around a defect that does not exist — and a test
                // that drives an engine path by hand stops testing it, which is the worst possible
                // trade for a rung whose whole job is to exercise the engine. The two real causes of
                // "0 arrows shot" were the HAND (the bow sat in the bag) and the SEARCH BOX (128 was
                // narrower than the arena the dragon flies in).
                bowTicks++;
                if (bowBroken) { /* one failure is enough; melee still works */ }
                else {
                drawTicks = p.isUsingItem() ? p.getTicksUsingItem() : 0;
                if (drawTicks >= BOW_FULL_DRAW) {
                    arms.useKey(false);                    // up-edge = release = shoot
                    // Count AMMO, not releases. The first cut incremented here and reported 9516
                    // shots from a quiver of 256: once the arrows run out stopUsingItem still gets
                    // called every cycle, so the counter went on climbing while nothing was fired.
                    // A number that keeps rising after the thing it counts has stopped happening is
                    // worse than no number.
                    int now = p.getInventory().countItem(net.minecraft.world.item.Items.ARROW);
                    if (ammoBefore < 0) ammoBefore = now;
                    if (now < ammoBefore) { arrows += ammoBefore - now; ammoBefore = now; }
                } else if (p.isUsingItem()) {
                    arms.useKey(true);                     // already drawing: keep holding
                } else {
                    // RE-ARM. commandUseItem is edge-triggered on the avatar's own useHeld flag, so
                    // once vanilla stops the use by itself — which the melee branch's release does
                    // every time the dragon comes into reach — isUsingItem() goes false while
                    // useHeld stays true, and every later commandUseItem(true) is a no-op. The draw
                    // is then dead for the rest of the fight. Measured: "ranged branch entered for
                    // 21982 ticks, draw count 7, arrows in stock 256" — it reached 7, was stopped
                    // once, and never drew again in the remaining 21975 ticks. The down-edge resets
                    // the flag; the up-edge starts a real draw.
                    // The down-edge is safe here and does NOT waste an arrow: we only reach this
                    // branch when isUsingItem() is false, i.e. useItem is already EMPTY, and
                    // releaseUsingItem() skips the fire on an empty stack and just resets.
                    arms.useKey(false);
                    arms.useKey(true);
                }
                }
            }
            } catch (RuntimeException e) {
                // Its OWN key. Writing this into `why` would let the later give-up message overwrite
                // it, so an exception on tick 30 vanished behind "gave up" on tick 21102 — one key, two
                // writers, last one wins, and the first write was the interesting one.
                bowError = String.valueOf(e);
                bowBroken = true;
            }
            // Never once in reach for DUEL_OUT_OF_REACH_TICKS: stop waiting. Reset by any approach,
            // so this ends a duel the bot is not in, not a fight with lulls. An arrow in flight
            // counts as being in the fight — giving up while landing hits would report "not in
            // position" about a bot that is winning.
            if (aim == null && arrows == arrowsAtLastReach) {
                if (++outOfReach >= DUEL_OUT_OF_REACH_TICKS) {
                    gaveUp = true;
                    why = "gave up: out of reach for " + outOfReach + " consecutive ticks";
                    return true;
                }
            } else {
                outOfReach = 0;
                arrowsAtLastReach = arrows;
            }
            if (++elapsed >= maxTicks) { why = "budget exhausted (" + maxTicks + " ticks)"; return true; }
            return false;
        }

        int swings() { return swings; }

        /** True when it stopped because the dragon never came within reach, not because time ran
         *  out — the two look identical from the outside and mean different things. */
        boolean gaveUp() { return gaveUp; }

        String why() { return why; }

        /** Everything about the ranged half, as one row. Five runs reported "0 arrows shot" and each
         *  time the cause was somewhere else entirely — wrong hand, a suspected frozen use-timer, a
         *  search box narrower than the arena. A count of zero says nothing about which. */
        String bowRow(Player p) {
            return "ranged branch entered for " + bowTicks + " ticks, draw count " + drawTicks
                    + ", arrows in stock " + (p == null ? -1
                        : p.getInventory().countItem(net.minecraft.world.item.Items.ARROW))
                    + ", main hand=" + (p == null ? "?" : p.getMainHandItem().getItem())
                    + (bowError == null ? "" : ", exception=" + bowError);
        }

        int arrows() { return arrows; }

        int elapsedTicks() { return elapsed; }

        /** Vanilla's own full-draw window: 20 ticks of use is maximum power. */
        private static final int BOW_FULL_DRAW = 20;

        /** Put the wanted item in the main hand, swapping up from the bag if need be, or say it is
         *  not carried. Reading the main hand alone was worth exactly zero arrows: the bot walks
         *  this rung holding a sword or a stack of cobblestone, so a draw gated on "bow in hand" never
         *  ran once in a whole fight while 256 arrows sat in the bag. Owning is not holding —
         *  the same shape that cost this rung its buckets. */
        private static boolean select(Player p, java.util.function.Predicate<ItemStack> want) {
            var inv = p.getInventory();
            if (want.test(p.getMainHandItem())) return true;
            for (int i = 0; i < 9; i++)
                if (want.test(inv.items.get(i))) { inv.selected = i; return true; }
            for (int i = 9; i < inv.items.size(); i++) {
                if (!want.test(inv.items.get(i))) continue;
                ItemStack bag = inv.items.get(i);
                inv.items.set(i, inv.items.get(inv.selected));
                inv.items.set(inv.selected, bag);
                return true;
            }
            return false;
        }

        private static boolean holdBow(Player p) {
            return select(p, st -> st.getItem() instanceof net.minecraft.world.item.BowItem);
        }

        /** A bow in the main hand melees for 1. Swinging without this swap turns every perch —
         *  the only window vanilla gives full damage — into a wasted one. */
        private static void holdSword(Player p) {
            select(p, st -> st.getItem() instanceof net.minecraft.world.item.SwordItem);
        }

        private static void aimAtPart(Player p, Entity part, double lead) {
            double dx = part.getX() - p.getX();
            double dy = (part.getY() + part.getBbHeight() * 0.5 + lead) - (p.getY() + p.getEyeHeight());
            double dz = part.getZ() - p.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);
            p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
            p.setXRot((float) -Math.toDegrees(Math.atan2(dy, flat)));
            p.yHeadRot = p.getYRot();
            p.yBodyRot = p.getYRot();
        }

        int headHits() { return headHits; }

        double closest() { return closest == Double.MAX_VALUE ? -1 : closest; }

        String closestPart() { return closestPart; }
    }
}
