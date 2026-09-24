package net.magicterra.worlddriver.bot.stagewright.journey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Eat, then wait for the health that eating makes possible — the leg the ladder never had.
 *
 * <p><b>Why it exists.</b> On 2026-08-26 the iron rung fell three times down its own shaft (−4, −7,
 * −3, every {@code hp.trace} row placing the bot in air), banked its three ingots and PASSED: its
 * assertion asks for ingots, not for a bot able to continue. The gravel rung then aborted on its
 * FIRST tick, because {@code MineProcess} refuses to mine at or below {@code MINE_HP_CRITICAL}=4
 * and the bot arrived at exactly 4.0 — {@code broke 0/64}, no gravel, no flint, and a rung
 * reporting "10% drop rate, relying on volume rather than luck" about a die it never rolled. Both
 * rungs behaved correctly.
 * What was missing is that nobody spends the five raw beef the food rung banked.
 *
 * <p><b>Why not the engine's own toggles.</b> {@code BotConfig.autoHeal} and {@code autoEat} exist
 * and are off by default. Turning them on arms a preemption for all twenty rungs at once, so every
 * change in the next run becomes unattributable — that, and not the use key itself, is the
 * objection. ⚠️ Holding the use key is not "a key press in a tick": {@link #startBite} holds it,
 * because the ban is on hammering a key every tick as a drive loop, not on
 * {@code net.magicterra.worlddriver.bot.body.Hands#commandUseItem}, which is an edge-triggered
 * actuator verb the engine steers its own bow with. An explicit eating step eats where eating is
 * wanted and nowhere else.
 *
 * <p><b>What it does NOT assert.</b> Nothing here fails a rung. Every branch records what it saw
 * and calls {@code then}: a body with no food, a hold that would not take, a bite that never
 * started, a wait that timed out — the rung proceeds in exactly the state it would have been in
 * without this leg, and the evidence says which of those happened. That is deliberate for the first
 * run: whether a client-driven {@code ServerPlayer} can be made to eat from the server side at all
 * is an open question (server-written state has been lost to the next client packet before — see
 * the aiming subsystem), and an instrument that fails the rung would answer it by killing the run.
 *
 * <p><b>What the first real occasion returned</b>, on the gravel rung of 2026-08-26: it fired on
 * FOOD, not on health — health 20.0/20.0, food 8/20. Health has been full on that rung both runs
 * that reached it, while hunger has been under {@link #REGEN_FOOD} from the food rung onward in
 * every run, so the hunger half of the condition is the half that gets used, and this leg speaks on
 * every climb rather than only after a bad fall. The bite then landed in the one ending no branch
 * had a name for: the food was found, the hold took, {@code startUsingItem} took, the wait returned
 * — and the bar read {@code 8→8}. Start and finish both happened; the middle did not. {@link Bite}
 * exists to say which middle.
 *
 * <p><b>And it answered on its first run.</b> The middle was never a middle: the hand held a BUCKET
 * at the instant the use began, so a zero-duration item was "eaten" and the flag cleared one tick
 * later. Neither of the two candidates the trace was built to separate — an outside
 * {@code stopUsingItem}, or a completion that fed nothing — was the answer, and neither would have
 * been found by reasoning about food. What settled it is that {@link Bite} prints the item's ID
 * rather than a same/different boolean: the two ids matched each other and neither was the food.
 *
 * <p><b>And the run after that showed the wait was half of it.</b> Run 10 held the right item —
 * {@code held=minecraft:beef, using=minecraft:beef}, so the bucket family is closed and the
 * {@code updatingUsingItem} mismatch branch is ruled out — and the bite still died at
 * {@code useItemRemaining=31}, two ticks into thirty-two. A bite that ends two ticks in with the
 * hand correct is ended by something outside this file, and the suspect is the client: a
 * {@code LocalPlayer} whose use key was never pressed sees the synced "using" flag and releases it.
 *
 * <p><b>So the bite is begun the way the engine begins one.</b> {@code net.magicterra.worlddriver.bot.body.Hands#commandUseItem} holds
 * the client's own use key — the route {@code CombatProcess} draws a bow with and the one rung 20
 * shoots the dragon with — and a use the client started is not one it takes back. See
 * {@link #startBite}. {@link #finishTheBite} stays behind it as a recorded fallback, so a run where
 * the held key still is not enough carries its own counter-evidence rather than a green row.
 */
final class JourneyFeed {

    private JourneyFeed() {}

    /** Cooked before raw: more saturation per bite, so fewer bites and fewer ticks. */
    private static final java.util.List<String> FOODS = java.util.List.of(
            "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_mutton",
            "minecraft:cooked_chicken", "minecraft:bread",
            "minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:chicken");

    /** Below this, a rung that mines cannot start: {@code MineProcess.MINE_HP_CRITICAL} is 4 and it
     *  aborts at or below it. Six leaves room for one bad step on the way to the dig. */
    private static final float LOW_HP = 6f;

    /** Vanilla regenerates nothing below 18 food, so this is the bar eating has to clear before
     *  waiting for health is anything but waiting. */
    private static final int REGEN_FOOD = 18;

    /** One bite is 32 ticks; 60 leaves room for the hold and the packet round-trip. */
    private static final int BITE_TICKS = 60;

    /** Most bites to take. Nine cooked beef would fill an empty bar; the ladder never carries that
     *  many, so this is a stop, not a target. */
    private static final int MAX_BITES = 8;

    /** Health regenerates about once every 80 ticks at this saturation, so this buys roughly six
     *  points. Its own budget on purpose: folded into the mine's, a slow regen would be recorded as
     *  a mining failure. */
    private static final int REGEN_TICKS = 600;

    /**
     * Eat if the body is low, wait for the regen, then continue — always continue.
     *
     * @param tag evidence prefix, so two callers on one rung stay apart
     */
    static void eatIfLow(JourneyRig rig, String tag, Runnable then) {
        ServerPlayer fp = rig.player();
        float hp = fp.getHealth();
        int food = fp.getFoodData().getFoodLevel();
        rig.evidence(tag + ".feed.before", String.format("health %.1f/%.1f, food %d/20", hp,
                fp.getMaxHealth(), food));
        if (hp > LOW_HP && food >= REGEN_FOOD) {
            rig.evidence(tag + ".feed", "not needed: health " + String.format("%.1f", hp) + " > " + LOW_HP
                    + ", food " + food + " ≥ " + REGEN_FOOD);
            then.run();
            return;
        }
        rig.attempting("low health or low food: eat before working");
        bite(rig, tag, 0, then);
    }

    /** The first food in {@link #FOODS} the body owns, or null. A method rather than a loop with a
     *  mutable local because the result is captured by the bite's continuation. */
    private static String firstFoodOwned(JourneyRig rig) {
        for (String id : FOODS) if (rig.carrying(id) > 0) return id;
        return null;
    }

    /** One bite per call, recursing until the bar is up, the bag is empty, or the cap is hit. */
    private static void bite(JourneyRig rig, String tag, int n, Runnable then) {
        ServerPlayer fp = rig.player();
        if (n >= MAX_BITES || fp.getFoodData().getFoodLevel() >= 20) {
            afterEating(rig, tag, n, then);
            return;
        }
        final String chosen = firstFoodOwned(rig);
        if (chosen == null) {
            rig.evidence(tag + ".feed.food", n == 0
                    ? "no food in the inventory: this rung starts work at low health"
                    : "ate all food in the inventory, " + n + " bites in total");
            afterEating(rig, tag, n, then);
            return;
        }
        Item want = JourneyRig.item(chosen);
        if (!JourneyHands.holdBoth(rig, want)) {
            // Same failure the weapon hold reports: owning it and holding it are different questions.
            rig.evidence(tag + ".feed.hold", "could not put in hand: " + chosen + " (holding "
                    + rig.heldItemId() + ")");
            afterEating(rig, tag, n, then);
            return;
        }
        // THE HOLD IS A PACKET, NOT AN ASSIGNMENT — wait for it to land before starting the use.
        // The first run to get a trace here (2026-08-26) read "eating for 1 tick;
        // useItemRemaining=0; held at that moment=minecraft:bucket, using=minecraft:bucket"
        // with beef in slot 4 by the time the row was written. `holdBoth` returned true, and it was
        // telling the truth about what it had SENT; the client's swap click had not reached the
        // server yet, so the server-side `startUsingItem` one line later picked up the bucket the
        // portal-kit rung had just crafted. A bucket's use duration is zero, so the flag cleared on
        // the next tick and the bar never moved. `JourneyHands.holdBoth`'s own javadoc records the
        // same shape from rung 12's `water6`, where the click landed BEFORE the use packet and made
        // the client the single correct author — but that ordering guarantee belongs to actions sent
        // over the connection, and this one is a direct server call, which reads the hand as it is
        // right now. So this waits for the hand rather than trusting the send.
        awaitHand(rig, tag, n, want, chosen, () -> startBite(rig, tag, n, chosen, then), then);
    }

    /** How long to let the hold's packet land. Twenty ticks is a second — far longer than a
     *  round-trip on an integrated server, and short enough that a hold that will never land does
     *  not eat the rung's budget. */
    private static final int HOLD_TICKS = 20;

    /** Wait for the SERVER's hand to be the food, then eat; on timeout say so and carry on. */
    private static void awaitHand(JourneyRig rig, String tag, int n, Item want, String chosen,
                                  Runnable eat, Runnable then) {
        ServerPlayer fp = rig.player();
        if (fp.getMainHandItem().getItem() == want) { eat.run(); return; }
        rig.await(() -> rig.player().getMainHandItem().getItem() == want, HOLD_TICKS, () -> {
            if (rig.player().getMainHandItem().getItem() != want) {
                rig.evidence(tag + ".feed.holdLate" + n, "after waiting " + HOLD_TICKS
                        + " ticks the server-side hand is still not " + chosen + " (it is "
                        + rig.heldItemId() + "); not eating, since it would consume something else");
                afterEating(rig, tag, n, then);
                return;
            }
            eat.run();
        });
    }

    /**
     * The bite itself, entered only once the hand is known to hold the food.
     *
     * <p><b>Through the avatar, not through {@code startUsingItem}.</b> The first two runs drove
     * this by calling {@code fp.startUsingItem(MAIN_HAND)} on the server and then watching the flag
     * — and on a client-driven body the flag went out two ticks into a thirty-two-tick meal with
     * the hand correct. The engine has had the held use all along: {@code net.magicterra.worlddriver.bot.body.Hands#commandUseItem} is
     * how {@code CombatProcess} draws a bow and how rung 20 shoots the dragon, and on this topology
     * it resolves to {@code ClientPlayerBody}, which holds the CLIENT's use key. A bite begun by
     * the client is a bite the client will not take back. Driving the engine path by hand also
     * stops testing it — {@code JourneyEndRungs}' bow comment paid for that lesson once already.
     *
     * <p><b>Down-edge first.</b> {@code ServerPlayerBody}'s side of this verb is edge-triggered on
     * its own {@code useHeld} flag, so a stale {@code true} makes every later {@code (true)} a
     * no-op — the failure that cost the dragon fight 21975 ticks of dead bow. Releasing first costs
     * nothing on either implementation.
     *
     * <p><b>The wait is on the BAR, not on the flag.</b> With the key held, vanilla starts the next
     * use a few ticks after one finishes, so "still using" never cleanly goes false and a flag
     * watcher would time out through a meal that was working. Hunger rising is the thing actually
     * being asked about.
     *
     * <p>⚠️ The key is a shared global that {@code BotInteract.releaseKeys()} deliberately does not
     * clear (see {@code UseKeyOwnershipTest}); leaking it leaves the body walking with right-click
     * held. It is released in the continuation, which {@code rig.await} runs on timeout and on
     * body death as well as on success.
     */
    private static void startBite(JourneyRig rig, String tag, int n, String chosen, Runnable then) {
        ServerPlayer fp = rig.player();
        int foodBefore = fp.getFoodData().getFoodLevel();
        var av = rig.hands();
        av.commandUseItem(false);
        av.commandUseItem(true);
        Bite trace = new Bite();
        rig.await(() -> {
            if (fp.isUsingItem()) trace.sample(fp);
            return fp.getFoodData().getFoodLevel() > foodBefore;
        }, BITE_TICKS, () -> {
            av.commandUseItem(false);
            int after = fp.getFoodData().getFoodLevel();
            rig.evidence(tag + ".feed.bite" + n, chosen + ": food " + foodBefore + "→" + after);
            rig.evidence(tag + ".feed.bite" + n + ".trace", trace.line(fp, rig));
            if (after > foodBefore) { bite(rig, tag, n + 1, then); return; }
            // Started and finished without feeding, and the trace says which of the two middles it
            // was. Cut short — the clock still had ticks on it — is somebody ELSE ending this bite,
            // and that is the one case a completion can honestly stand in for.
            if (trace.wasCutShort() && finishTheBite(rig, tag, n, chosen)) {
                bite(rig, tag, n + 1, then);
                return;
            }
            rig.evidence(tag + ".feed.stalled", "a bite did not raise food, stopped eating ("
                    + (n + 1) + " bites)");
            afterEating(rig, tag, n + 1, then);
        });
    }

    /**
     * Finish, server-side, a bite that something else cut short — and say in the row that it was
     * FINISHED rather than eaten, because those are not the same claim.
     *
     * <p><b>What cuts it.</b> Measured on the gravel rung, run 10 of 2026-08-26, with the hand
     * already correct: "eating for 2 ticks; useItemRemaining=31; held=minecraft:beef,
     * using=minecraft:beef". Hand and use agree, so this is not the {@code updatingUsingItem} mismatch
     * branch. The remaining suspect is the client: {@code isUsingItem} rides on synced entity flags,
     * so a {@code LocalPlayer} whose use key was never pressed sees itself using an item and sends
     * {@code RELEASE_USE_ITEM} on its next tick. This repo's own {@link
     * net.magicterra.worlddriver.bot.auto.AutoEat} is the corroboration: it eats by HOLDING
     * the use intent and releasing at food=20, which is only necessary if letting go ends the
     * bite. ⚠️ Corroboration is not proof — nothing here has yet watched that packet arrive. What
     * exists is one half of the comparison: {@code wd.serverAvatarTickFidelity} (A) holds the use
     * on cooked beef for forty ticks and requires the meal to finish, and it is GREEN — on a
     * {@code SceneBody.mint} SERVER avatar, which has no client to release anything. The missing
     * half is the same measurement on a client-driven body.
     *
     * <p><b>A fallback, not the route.</b> {@link #startBite} holds the client's own use key
     * through {@code net.magicterra.worlddriver.bot.body.Hands#commandUseItem}, which is how the engine draws a bow; this runs only
     * when even that came back with the bar unmoved and the clock cut short. It is not an imitation
     * of a bite's ending — {@link ItemStack#finishUsingItem} is the call
     * {@code LivingEntity.completeUsingItem} itself makes, so nutrition, saturation, stack shrink
     * and any effects land through the same path a full 32-tick bite would use. It is still a hand
     * drive of an engine path, which stops testing that path — hence the row, and hence its being
     * second.
     *
     * @return whether the bar actually moved; false leaves the caller's stall row to be written.
     */
    private static boolean finishTheBite(JourneyRig rig, String tag, int n, String chosen) {
        ServerPlayer fp = rig.player();
        ItemStack hand = fp.getMainHandItem();
        if (hand.isEmpty() || hand.getItem() != JourneyRig.item(chosen)) {
            rig.evidence(tag + ".feed.finish" + n, "not completing: the hand no longer holds " + chosen
                    + " (it holds " + rig.heldItemId() + "); completing something else is worse than"
                    + " not completing");
            return false;
        }
        int before = fp.getFoodData().getFoodLevel();
        fp.setItemInHand(InteractionHand.MAIN_HAND, hand.finishUsingItem(fp.serverLevel(), fp));
        int after = fp.getFoodData().getFoodLevel();
        rig.evidence(tag + ".feed.finish" + n, "this bite was completed on the server, not run to its"
                + " own end (" + chosen + " via ItemStack.finishUsingItem, the same path as"
                + " completeUsingItem): food " + before + "→" + after);
        return after > before;
    }

    /**
     * Eating is over; wait for the health it enables, then hand back regardless.
     *
     * <p>The first line releases the use key unconditionally. {@link #startBite} already releases
     * in its own continuation, but a continuation is not a guarantee: a scene that hard-fails
     * mid-{@code await} never reaches one, and the use intent outlives the scene — a
     * gravel rung that dies mid-bite would hand rung 11 a body walking around with right-click
     * held, exactly the leak {@code UseKeyOwnershipTest} names. This method is where every exit of
     * the leg converges, and releasing twice costs nothing (that test calls releases unrestricted).
     */
    private static void afterEating(JourneyRig rig, String tag, int bites, Runnable then) {
        rig.hands().commandUseItem(false);
        ServerPlayer fp = rig.player();
        float hp = fp.getHealth();
        int food = fp.getFoodData().getFoodLevel();
        rig.evidence(tag + ".feed.after", String.format("ate %d bites, health %.1f/%.1f, food %d/20",
                bites, hp, fp.getMaxHealth(), food));
        if (hp > LOW_HP) { then.run(); return; }
        if (food < REGEN_FOOD) {
            rig.evidence(tag + ".feed.regen", "not waiting: food " + food + " < " + REGEN_FOOD
                    + ", so natural regeneration will not happen");
            then.run();
            return;
        }
        rig.attempting("wait for natural regeneration");
        rig.await(() -> fp.getHealth() > LOW_HP, REGEN_TICKS, () -> {
            rig.evidence(tag + ".feed.regen", String.format("regeneration wait over: health %.1f (wanted > %.1f)",
                    fp.getHealth(), LOW_HP));
            then.run();
        });
    }

    /**
     * One bite's per-tick trace — the reading that tells three identical-looking endings apart.
     *
     * <p>The first run to reach here ate nothing and left exactly two rows: {@code isUsingItem}
     * back to false, food unchanged. Three mechanisms produce that pair and they want opposite
     * fixes:
     *
     * <ul>
     *   <li>the counter never ran down, so something outside called {@code stopUsingItem} — the
     *       client-packet family, the same shape the aiming subsystem loses angles to;</li>
     *   <li>the counter reached zero and {@code completeUsingItem} still fed nothing;</li>
     *   <li>the stack in the hand stopped matching {@code getUseItem()}, which is
     *       {@code LivingEntity.updatingUsingItem}'s own mismatch branch calling
     *       {@code stopUsingItem} — a hold landing mid-bite, not a lost packet.</li>
     * </ul>
     *
     * <p>{@code useItemRemaining} separates the first from the other two and the two item ids
     * separate the third, so one row decides it. Sampling lives inside the await predicate because
     * that is the only hook running on every tick of the wait; what it samples never decides what
     * the predicate returns.
     */
    private static final class Bite {
        private int ticks;
        private int remaining = -1;
        private String hand;
        private String using;

        void sample(ServerPlayer fp) {
            ticks++;
            remaining = fp.getUseItemRemainingTicks();
            hand = id(fp.getItemInHand(InteractionHand.MAIN_HAND));
            using = id(fp.getUseItem());
        }

        /** The flag went out with ticks still on the clock, so the bite did not end itself. A bite
         *  that ran to {@code remaining == 0} and still fed nothing is a DIFFERENT disease, and the
         *  server-side completion must not be allowed to paper over it — hence the reading rather
         *  than "it fed nothing" as the trigger. {@code ticks == 0} never observed a clock at all. */
        boolean wasCutShort() { return ticks > 0 && remaining > 0; }

        String line(ServerPlayer fp, JourneyRig rig) {
            String now = "; server-side selected slot now " + fp.getInventory().selected + ", held="
                    + rig.heldItemId();
            if (ticks == 0) {
                return "not a single tick of \"still eating\" observed during the whole wait: the client"
                        + " never started this bite after the use key was held (not cut short; a cut"
                        + " would show at least one tick)" + now;
            }
            return "eating for " + ticks + " ticks; last useItemRemaining read before the flag went false="
                    + remaining + " (a bite is 32 ticks and completeUsingItem runs only when the count"
                    + " reaches 0, so >1 means something else cut it short)"
                    + "; held at that moment=" + hand + ", using=" + using
                    + (hand != null && !hand.equals(using)
                            ? " ⚠️ the two differ: this is the branch where updatingUsingItem itself"
                              + " calls stopUsingItem"
                            : "")
                    + now;
        }

        private static String id(ItemStack s) {
            return s == null || s.isEmpty() ? "empty"
                    : String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()));
        }
    }
}
