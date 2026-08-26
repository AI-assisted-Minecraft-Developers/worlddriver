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
 * −3, every {@code hp.trace} row reading {@code 身处=air}), banked its three ingots and PASSED: its
 * assertion asks for ingots, not for a body able to continue. The gravel rung then aborted on its
 * FIRST tick, because {@code MineProcess} refuses to mine at or below {@code MINE_HP_CRITICAL}=4
 * and the body arrived at exactly 4.0 — {@code broke 0/64}, no gravel, no flint, and a rung
 * reporting「10% 掉率，靠量不靠运气」about a die it never rolled. Both rungs behaved correctly.
 * What was missing is that nobody spends the five raw beef the food rung banked.
 *
 * <p><b>Why not the engine's own toggles.</b> {@code BotConfig.autoHeal} and {@code autoEat} exist
 * and are off by default. Turning them on would arm a preemption for all twenty rungs at once, so
 * the next run's every change becomes unattributable, and {@code AutoHeal.engage} does its work
 * through {@code mc.options.keyUse.setDown(true)} — a key press in a tick, which this repo drives
 * with direct calls and packets instead. An explicit leg eats where eating is wanted and nowhere
 * else.
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
 * FOOD, not on health — {@code 血 20.0/20.0，饱食 8/20}. Health has been full on that rung both runs
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
 * {@code 手里=minecraft:beef、正在用的是=minecraft:beef}, so the bucket family is closed and the
 * {@code updatingUsingItem} mismatch branch is ruled out — and the bite still died at
 * {@code useItemRemaining=31}, two ticks into thirty-two. A bite that ends two ticks in with the
 * hand correct is ended by something outside this file; {@link #finishTheBite} carries the leg past
 * it and names the suspect, and the row it writes never claims the body ate.
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
        rig.evidence(tag + ".feed.before", String.format("血 %.1f/%.1f，饱食 %d/20", hp,
                fp.getMaxHealth(), food));
        if (hp > LOW_HP && food >= REGEN_FOOD) {
            rig.evidence(tag + ".feed", "不需要：血 " + String.format("%.1f", hp) + " > " + LOW_HP
                    + "，饱食 " + food + " ≥ " + REGEN_FOOD);
            then.run();
            return;
        }
        rig.attempting("低血/低饱食，先吃东西再干活");
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
                    ? "包里一样吃的都没有 —— 这一级要低血开工了"
                    : "吃完了包里所有食物，共 " + n + " 口");
            afterEating(rig, tag, n, then);
            return;
        }
        Item want = JourneyRig.item(chosen);
        if (!JourneyHands.holdBoth(rig, want)) {
            // Same failure the weapon hold reports: owning it and holding it are different questions.
            rig.evidence(tag + ".feed.hold", "拿不到手上：" + chosen + "（手里是 " + rig.heldItemId() + "）");
            afterEating(rig, tag, n, then);
            return;
        }
        // THE HOLD IS A PACKET, NOT AN ASSIGNMENT — wait for it to land before starting the use.
        // The first run to get a trace here (2026-08-26) read
        // 「还在吃了 1 tick；useItemRemaining=0；那一刻手里=minecraft:bucket、正在用的是=minecraft:bucket」
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
                rig.evidence(tag + ".feed.holdLate" + n, "等了 " + HOLD_TICKS + " tick，服务端手里仍不是 "
                        + chosen + "（是 " + rig.heldItemId() + "）—— 不开吃，否则吃的是别的东西");
                afterEating(rig, tag, n, then);
                return;
            }
            eat.run();
        });
    }

    /** The bite itself, entered only once the hand is known to hold the food. */
    private static void startBite(JourneyRig rig, String tag, int n, String chosen, Runnable then) {
        ServerPlayer fp = rig.player();
        int foodBefore = fp.getFoodData().getFoodLevel();
        fp.startUsingItem(InteractionHand.MAIN_HAND);
        if (!fp.isUsingItem()) {
            // The open question, answered on the first run that gets here: a server-side
            // startUsingItem on a client-driven body either takes or it does not.
            rig.evidence(tag + ".feed.bite" + n, "startUsingItem 之后 isUsingItem 仍为 false —— "
                    + "这具身体不接受服务端发起的进食（手里=" + rig.heldItemId() + "）");
            afterEating(rig, tag, n, then);
            return;
        }
        Bite trace = new Bite();
        rig.await(() -> {
            if (fp.isUsingItem()) { trace.sample(fp); return false; }
            return true;
        }, BITE_TICKS, () -> {
            int after = fp.getFoodData().getFoodLevel();
            rig.evidence(tag + ".feed.bite" + n, chosen + "：饱食 " + foodBefore + "→" + after);
            rig.evidence(tag + ".feed.bite" + n + ".trace", trace.line(fp, rig));
            if (after > foodBefore) { bite(rig, tag, n + 1, then); return; }
            // Started and finished without feeding, and the trace says which of the two middles it
            // was. Cut short — the clock still had ticks on it — is somebody ELSE ending this bite,
            // and that is the one case a completion can honestly stand in for.
            if (trace.wasCutShort() && finishTheBite(rig, tag, n, chosen)) {
                bite(rig, tag, n + 1, then);
                return;
            }
            rig.evidence(tag + ".feed.stalled", "一口下去饱食没涨，停止进食（" + (n + 1) + " 口）");
            afterEating(rig, tag, n + 1, then);
        });
    }

    /**
     * Finish, server-side, a bite that something else cut short — and say in the row that it was
     * FINISHED rather than eaten, because those are not the same claim.
     *
     * <p><b>What cuts it.</b> Measured on the gravel rung, run 10 of 2026-08-26, with the hand
     * already correct: {@code 还在吃了 2 tick；useItemRemaining=31；手里=minecraft:beef、正在用的是
     * =minecraft:beef}. Hand and use agree, so this is not the {@code updatingUsingItem} mismatch
     * branch. The remaining suspect is the client: {@code isUsingItem} rides on synced entity flags,
     * so a {@code LocalPlayer} whose use key was never pressed sees itself using an item and sends
     * {@code RELEASE_USE_ITEM} on its next tick. This repo's own {@link
     * net.magicterra.worlddriver.bot.auto.AutoEat} is the corroboration: it eats by HOLDING
     * {@code keyUse} down and releasing at food=20, which is only necessary if letting go ends the
     * bite. ⚠️ Corroboration is not proof — nothing here has yet watched that packet arrive, and
     * the settling measurement is an arena scene that starts a bite on BOTH bodies, since a joined
     * body has no client to send it.
     *
     * <p><b>Why this and not a key.</b> Holding {@code keyUse} is the one route this repo does not
     * take («tick 里面不要驱动按键»), and arming {@code BotConfig.autoEat} would preempt all twenty
     * rungs at once. {@link ItemStack#finishUsingItem} is not an imitation of a bite's ending — it
     * is the call {@code LivingEntity.completeUsingItem} itself makes, so nutrition, saturation,
     * stack shrink and any effects land through the same path a full 32-tick bite would use.
     *
     * @return whether the bar actually moved; false leaves the caller's stall row to be written.
     */
    private static boolean finishTheBite(JourneyRig rig, String tag, int n, String chosen) {
        ServerPlayer fp = rig.player();
        ItemStack hand = fp.getMainHandItem();
        if (hand.isEmpty() || hand.getItem() != JourneyRig.item(chosen)) {
            rig.evidence(tag + ".feed.finish" + n, "不补完：手里已经不是 " + chosen
                    + "（是 " + rig.heldItemId() + "）—— 补完别的东西比不补更糟");
            return false;
        }
        int before = fp.getFoodData().getFoodLevel();
        fp.setItemInHand(InteractionHand.MAIN_HAND, hand.finishUsingItem(fp.serverLevel(), fp));
        int after = fp.getFoodData().getFoodLevel();
        rig.evidence(tag + ".feed.finish" + n, "这一口是服务端补完的，不是自己走完的（"
                + chosen + " 走 ItemStack.finishUsingItem，与 completeUsingItem 同一条路）"
                + "：饱食 " + before + "→" + after);
        return after > before;
    }

    /** Eating is over; wait for the health it enables, then hand back regardless. */
    private static void afterEating(JourneyRig rig, String tag, int bites, Runnable then) {
        ServerPlayer fp = rig.player();
        float hp = fp.getHealth();
        int food = fp.getFoodData().getFoodLevel();
        rig.evidence(tag + ".feed.after", String.format("吃了 %d 口，血 %.1f/%.1f，饱食 %d/20",
                bites, hp, fp.getMaxHealth(), food));
        if (hp > LOW_HP) { then.run(); return; }
        if (food < REGEN_FOOD) {
            rig.evidence(tag + ".feed.regen", "不等了：饱食 " + food + " < " + REGEN_FOOD
                    + "，自然回血不会发生");
            then.run();
            return;
        }
        rig.attempting("等自然回血");
        rig.await(() -> fp.getHealth() > LOW_HP, REGEN_TICKS, () -> {
            rig.evidence(tag + ".feed.regen", String.format("等回血结束：血 %.1f（想要 > %.1f）",
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
         *  than「it fed nothing」as the trigger. {@code ticks == 0} never observed a clock at all. */
        boolean wasCutShort() { return ticks > 0 && remaining > 0; }

        String line(ServerPlayer fp, JourneyRig rig) {
            String now = "；此刻服务端选中槽 " + fp.getInventory().selected + "，手里=" + rig.heldItemId();
            if (ticks == 0) {
                return "一 tick 都没观察到「还在吃」—— 标志在 startUsingItem 之后、第一次 await 之前"
                        + "就已经没了，所以这一口连一个 tick 都没活过" + now;
            }
            return "还在吃了 " + ticks + " tick；翻回 false 前最后一次读到 useItemRemaining=" + remaining
                    + "（一口 32 tick，倒数到 0 才会 completeUsingItem，所以 >1 就是被别人掐掉的）"
                    + "；那一刻手里=" + hand + "、正在用的是=" + using
                    + (hand != null && !hand.equals(using)
                            ? " ⚠️ 两者不同 —— 正是 updatingUsingItem 自己会 stopUsingItem 的那一支"
                            : "")
                    + now;
        }

        private static String id(ItemStack s) {
            return s == null || s.isEmpty() ? "空"
                    : String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()));
        }
    }
}
