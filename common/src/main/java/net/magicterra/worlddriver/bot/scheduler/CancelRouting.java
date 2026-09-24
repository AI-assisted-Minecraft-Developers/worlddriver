package net.magicterra.worlddriver.bot.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure routing for a targeted {@code mc.bot.cancel{process:<name>}} (gap#72-②).
 * Decides WHAT a named cancel hits — the user-task slot, a chain's internal
 * episode by NAME, a chain-HELD process by KIND — and shapes the honest result.
 * Server-safe and side-effect free so the routing semantics are matrix-testable
 * on the dedicated GameTest server; {@code BotApiImpl.cancel} executes the plan
 * (cancelCurrent / {@link Chain#cancelEpisode}) on the client thread.
 */
public final class CancelRouting {

    /** Resolved targets of one named cancel. {@code cancelUserProcess} = the
     *  user-task slot's process matched by kind; {@code episodeTargets} = chains
     *  whose episode/held process must be cancelled via {@link Chain#cancelEpisode};
     *  {@code labels} = distinguishable descriptions of every hit, in routing order,
     *  for the honest {@code cancelled} result field. */
    public record Plan(boolean cancelUserProcess, List<Chain> episodeTargets, List<String> labels) {}

    private CancelRouting() {}

    /**
     * Resolve a named cancel ({@code which} is never "all" — the caller handles
     * that broadcast separately) against the user slot and the registered chains,
     * in order:
     * <ol>
     *   <li><b>user slot by kind</b> — the user-verb process (P1-⑦ semantics),
     *       labelled {@code user/<kind>-process};</li>
     *   <li><b>chain episode by NAME</b> — only a LIVE episode
     *       ({@link Chain#episodePhase} non-null) counts as a hit, labelled
     *       {@code <name>-episode}. An idle chain's {@code cancelEpisode} is a
     *       documented no-op, so "hit" would be a lie (the gap#72-② live incident:
     *       byName("bunker") found the idle BunkerChain and cancel said ok:true
     *       while duskSecure's held BunkerProcess dug on untouched);</li>
     *   <li><b>chain-HELD process by KIND</b> — any chain whose
     *       {@link Chain#heldProcessKind} matches, labelled
     *       {@code <chainName>/<kind>-process} (e.g. {@code duskSecure/bunker-process}).
     *       Cancelled through {@link Chain#cancelEpisode}, i.e. the unified
     *       {@link ChainProcessLifecycle} drop from gap#72-①.</li>
     * </ol>
     * All matching routing steps are taken (labels keep them distinguishable), so a request
     * like {@code runAway} reaches both a user flee and the retreat reflex's flee.
     */
    public static Plan resolve(String which, String userProcessKind, List<Chain> chains) {
        List<Chain> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean user = userProcessKind != null && userProcessKind.equals(which);
        if (user) labels.add("user/" + which + "-process");
        Chain byName = null;
        for (Chain ch : chains) {
            if (which.equals(ch.name())) { byName = ch; break; }
        }
        if (byName != null && byName.episodePhase() != null) {
            targets.add(byName);
            labels.add(byName.name() + "-episode");
        }
        for (Chain ch : chains) {
            if (ch == byName) continue;               // already targeted by name
            if (which.equals(ch.heldProcessKind())) {
                targets.add(ch);
                labels.add(ch.name() + "/" + which + "-process");
            }
        }
        return new Plan(user, List.copyOf(targets), List.copyOf(labels));
    }

    /**
     * Shape the RPC result from the executed hits: {@code {ok:true, cancelled:
     * "<label>[,<label>…]"}} naming what was ACTUALLY cancelled, or an honest
     * {@code {ok:false, reason:"no-active-target", requested:<which>}} when nothing
     * matched — never the old unconditional ok:true (#280 silent-swallow shape).
     */
    public static Map<String, Object> honestResult(List<String> cancelledLabels, String which) {
        if (cancelledLabels.isEmpty()) {
            return Map.of("ok", false, "reason", "no-active-target", "requested", which);
        }
        return Map.of("ok", true, "cancelled", String.join(",", cancelledLabels));
    }
}
