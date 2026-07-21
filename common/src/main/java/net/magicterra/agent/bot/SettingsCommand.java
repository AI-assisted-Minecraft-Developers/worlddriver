package net.magicterra.agent.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import static net.magicterra.agent.AgentDriverCommon.LOG;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Applies {@code mc.bot.setting{...}} param mutations to {@link BotConfig} and
 * per-bot state, then returns the resulting settings snapshot. Extracted from
 * BotApiImpl; the impl's {@code setting(params)} delegates here.
 */
public final class SettingsCommand {

    private SettingsCommand() {}

    public static Map<String, Object> apply(BotApiImpl bot, Map<String, Object> params) {
        // Write path: any params keys that match a known setting + are in range are applied to
        // BotConfig immediately. #280 fix: an UNKNOWN key (one not in the single-source
        // SettingsRegistry) is REJECTED, not silently ignored. All-or-nothing — a call carrying
        // ANY unknown key throws BEFORE mutating anything, so nothing is half-applied. This is the
        // apply-side guard that backstops the closed mc.bot.setting schema (SchemaValidator already
        // rejects unknown keys at route() for every transport); direct callers hit this instead.
        // Out-of-range keys are still soft-rejected into rejected[] with ok:true.
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (params != null && !params.isEmpty()) {
            List<String> unknown = new ArrayList<>();
            for (String k : params.keySet()) {
                if (!SettingsRegistry.isKnown(k)) unknown.add(k);
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "mc.bot.setting: unknown key(s) " + unknown
                        + "; known keys: " + SettingsRegistry.knownKeys().size()
                        + ", see mc.bot.setting schema (all-or-nothing: nothing was applied)");
            }
        }
        if (params != null) {
            // Boolean toggle: paused absorbs the former mc.bot.pause / .resume —
            // {paused:true} stops all processes from advancing; {paused:false}
            // resumes the next tick. Released keys avoid a stuck attack.
            Object pv = params.get("paused");
            if (pv instanceof Boolean pb) {
                if (pb && !bot.paused) {
                    // Releasing keys requires the client thread; mirror pause().
                    onClient(() -> { releaseKeys(); return Map.of(); });
                }
                bot.paused = pb;
                applied.add("paused");
            }
            // Baritone-style survival toggles. Off→on flips just write the
            // flag; the live state is enforced on the next clientTick so we
            // don't need to hop to the client thread here.
            if (params.get("autoEat") instanceof Boolean ae) {
                BotConfig.autoEat = ae;
                applied.add("autoEat");
            }
            if (params.get("autoRespawn") instanceof Boolean ar) {
                BotConfig.autoRespawn = ar;
                applied.add("autoRespawn");
            }
            if (params.get("autoRetreat") instanceof Boolean art) {
                BotConfig.autoRetreat = art;
                applied.add("autoRetreat");
            }
            if (params.get("autoBunker") instanceof Boolean abk) {
                BotConfig.autoBunker = abk;
                applied.add("autoBunker");
            }
            if (params.get("autoTotem") instanceof Boolean ato) {
                BotConfig.autoTotem = ato;
                applied.add("autoTotem");
            }
            if (params.get("autoShield") instanceof Boolean ash) {
                BotConfig.autoShield = ash;
                applied.add("autoShield");
            }
            if (params.get("autoHeal") instanceof Boolean ah) {
                BotConfig.autoHeal = ah;
                applied.add("autoHeal");
            }
            if (params.get("autoDodge") instanceof Boolean ado) {
                BotConfig.autoDodge = ado;
                applied.add("autoDodge");
            }
            if (params.get("autoFight") instanceof Boolean af) {
                BotConfig.autoFight = af;
                applied.add("autoFight");
            }
            if (params.get("combatCrit") instanceof Boolean cc) {
                BotConfig.combatCrit = cc;
                applied.add("combatCrit");
            }
            if (params.get("combatCollectDrops") instanceof Boolean ccd) {
                BotConfig.combatCollectDrops = ccd;
                applied.add("combatCollectDrops");
            }
            if (params.get("autoEquip") instanceof Boolean ae2) {
                BotConfig.autoEquip = ae2;
                applied.add("autoEquip");
            }
            if (params.get("autoSwim") instanceof Boolean as) {
                BotConfig.autoSwim = as;
                applied.add("autoSwim");
            }
            if (params.get("antiSuffocate") instanceof Boolean asf) {
                BotConfig.antiSuffocate = asf;
                applied.add("antiSuffocate");
            }
            if (params.get("contactDamageEscape") instanceof Boolean cde) {
                BotConfig.contactDamageEscape = cde;
                applied.add("contactDamageEscape");
            }
            if (params.get("lavaProximityEscape") instanceof Boolean lpe) {
                BotConfig.lavaProximityEscape = lpe;
                applied.add("lavaProximityEscape");
            }
            if (params.get("allowParkour4") instanceof Boolean ap4) {
                BotConfig.allowParkour4 = ap4;
                applied.add("allowParkour4");
            }
            if (params.get("autoTool") instanceof Boolean at) {
                BotConfig.autoTool = at;
                applied.add("autoTool");
            }
            if (params.get("allowBreak") instanceof Boolean ab) {
                BotConfig.allowBreak = ab;
                applied.add("allowBreak");
            }
            if (params.get("allowSwimEscapeBreak") instanceof Boolean ase) {
                BotConfig.allowSwimEscapeBreak = ase;
                applied.add("allowSwimEscapeBreak");
            }
            if (params.get("allowSwimEscapePlace") instanceof Boolean asp) {
                BotConfig.allowSwimEscapePlace = asp;
                applied.add("allowSwimEscapePlace");
            }
            if (params.get("allowPlace") instanceof Boolean apl) {
                BotConfig.allowPlace = apl;
                applied.add("allowPlace");
            }
            if (params.get("allowParkourPlace") instanceof Boolean app) {
                BotConfig.allowParkourPlace = app;
                applied.add("allowParkourPlace");
            }
            if (params.get("allowWaterBucketFall") instanceof Boolean awb) {
                BotConfig.allowWaterBucketFall = awb;
                applied.add("allowWaterBucketFall");
            }
            if (params.get("waterBucketScoop") instanceof Boolean wbs) {
                BotConfig.waterBucketScoop = wbs;
                applied.add("waterBucketScoop");
            }
            if (params.get("avoidDanger") instanceof Boolean avd) {
                BotConfig.avoidDanger = avd;
                applied.add("avoidDanger");
            }
            if (params.get("lethalEdgeBrake") instanceof Boolean leb) {
                BotConfig.lethalEdgeBrake = leb;
                applied.add("lethalEdgeBrake");
            }
            if (params.get("lowHealthCareful") instanceof Number lhc) {
                BotConfig.lowHealthCareful = lhc.doubleValue();
                applied.add("lowHealthCareful");
            }
            if (params.get("descentCameraDecouple") instanceof Boolean dcd) {
                BotConfig.descentCameraDecouple = dcd;
                applied.add("descentCameraDecouple");
            }
            if (params.get("descentDecoupleLaunches") instanceof Boolean ddl) {
                BotConfig.descentDecoupleLaunches = ddl;
                applied.add("descentDecoupleLaunches");
            }
            if (params.get("autoSecureAtDusk") instanceof Boolean asad) {
                BotConfig.autoSecureAtDusk = asad;
                applied.add("autoSecureAtDusk");
            }
            if (params.get("pathfinder.dangerPenalty") instanceof Number dp) {
                double v = dp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.dangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.dangerPenaltyPerCell = v;
                    applied.add("pathfinder.dangerPenalty");
                }
            }
            if (params.get("pathfinder.lavaDangerPenalty") instanceof Number lp) {
                double v = lp.doubleValue();
                if (v < 0 || v > 5000) {
                    rejected.add("pathfinder.lavaDangerPenalty: out of range [0,5000]");
                } else {
                    BotConfig.lavaDangerPenalty = v;
                    applied.add("pathfinder.lavaDangerPenalty");
                }
            }
            if (params.get("pathfinder.contactDangerPenalty") instanceof Number cp) {
                double v = cp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.contactDangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.contactDangerPenalty = v;
                    applied.add("pathfinder.contactDangerPenalty");
                }
            }
            if (params.get("pathfinder.ledgeDangerPenalty") instanceof Number lgp) {
                double v = lgp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.ledgeDangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.ledgeDangerPenalty = v;
                    applied.add("pathfinder.ledgeDangerPenalty");
                }
            }
            if (params.get("pathfinder.ledgeDangerMinDrop") instanceof Number lmd) {
                int v = lmd.intValue();
                if (v < 1 || v > 64) {
                    rejected.add("pathfinder.ledgeDangerMinDrop: out of range [1,64]");
                } else {
                    BotConfig.ledgeDangerMinDrop = v;
                    applied.add("pathfinder.ledgeDangerMinDrop");
                }
            }
            if (params.get("pathfinder.waterDangerPenalty") instanceof Number wdp) {
                double v = wdp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.waterDangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.waterDangerPenalty = v;
                    applied.add("pathfinder.waterDangerPenalty");
                }
            }
            // Pathfinder budget setters (were read-only; needed to make replay measurement
            // DETERMINISTIC — a large sliceMs runs each A* search atomically so segment-commit
            // timing no longer varies with CPU load, killing the run-to-run churn variance).
            if (params.get("pathfinder.sliceMs") instanceof Number psm) {
                BotConfig.pathfinderSliceMs = psm.longValue();
                applied.add("pathfinder.sliceMs");
            }
            if (params.get("pathfinder.idleSliceMs") instanceof Number pism) {
                BotConfig.pathfinderIdleSliceMs = pism.longValue();
                applied.add("pathfinder.idleSliceMs");
            }
            if (params.get("pathfinder.maxMs") instanceof Number pmm) {
                BotConfig.pathfinderMaxMs = pmm.longValue();
                applied.add("pathfinder.maxMs");
            }
            if (params.get("pathfinder.maxNodes") instanceof Number pmn) {
                BotConfig.pathfinderMaxNodes = pmn.intValue();
                applied.add("pathfinder.maxNodes");
            }
            if (params.get("walkerDryWedgeFootY") instanceof Boolean dwfy) {
                BotConfig.walkerDryWedgeFootY = dwfy;
                applied.add("walkerDryWedgeFootY");
            }
            if (params.get("walkerWallCornerNodeAim") instanceof Boolean wcna) {
                BotConfig.walkerWallCornerNodeAim = wcna;
                applied.add("walkerWallCornerNodeAim");
            }
            if (params.get("walkerOvershootReaim") instanceof Boolean ora) {
                BotConfig.walkerOvershootReaim = ora;
                applied.add("walkerOvershootReaim");
            }
            if (params.get("walkerDryReanchor") instanceof Boolean dra) {
                BotConfig.walkerDryReanchor = dra;
                applied.add("walkerDryReanchor");
            }
            if (params.get("pathfinderDiagAscendPenalty") instanceof Number dap) {
                BotConfig.pathfinderDiagAscendPenalty = dap.doubleValue();
                applied.add("pathfinderDiagAscendPenalty");
            }
            if (params.get("walkerDiagDownCenter") instanceof Boolean ddc) {
                BotConfig.walkerDiagDownCenter = ddc;
                applied.add("walkerDiagDownCenter");
            }
            if (params.get("walkerWallCornerFastChurn") instanceof Boolean wcfc) {
                BotConfig.walkerWallCornerFastChurn = wcfc;
                applied.add("walkerWallCornerFastChurn");
            }
            if (params.get("walkerDrowningEscape") instanceof Boolean wde) {
                BotConfig.walkerDrowningEscape = wde;
                applied.add("walkerDrowningEscape");
            }
            if (params.get("walkerClimbGaveUpSticky") instanceof Boolean wcgs) {
                BotConfig.walkerClimbGaveUpSticky = wcgs;
                applied.add("walkerClimbGaveUpSticky");
            }
            if (params.get("walkerStepUpBackoffRetry") instanceof Boolean subr) {
                BotConfig.walkerStepUpBackoffRetry = subr;
                applied.add("walkerStepUpBackoffRetry");
            }
            if (params.get("walkerCarrotHColShrink") instanceof Boolean wchs) {
                BotConfig.walkerCarrotHColShrink = wchs;
                applied.add("walkerCarrotHColShrink");
            }
            if (params.get("walkerCarrotBodyLos") instanceof Boolean wcbl) {
                BotConfig.walkerCarrotBodyLos = wcbl;
                applied.add("walkerCarrotBodyLos");
            }
            if (params.get("walkerRouteHysteresis") instanceof Boolean wrhy) {
                BotConfig.walkerRouteHysteresis = wrhy;
                applied.add("walkerRouteHysteresis");
            }
            if (params.get("walkerBankDigGroundBlip") instanceof Boolean wbgb) {
                BotConfig.walkerBankDigGroundBlip = wbgb;
                applied.add("walkerBankDigGroundBlip");
            }
            if (params.get("walkerExpectAlarm") instanceof Boolean wea) {
                BotConfig.walkerExpectAlarm = wea;
                applied.add("walkerExpectAlarm");
            }
            if (params.get("avoidMobs") instanceof Boolean avm) {
                BotConfig.avoidMobs = avm;
                applied.add("avoidMobs");
            }
            if (params.get("walkerDebug") instanceof Boolean wd) {
                BotConfig.walkerDebug = wd;
                applied.add("walkerDebug");
            }
            if (params.get("walkerVerticalResync") instanceof Boolean wvr) {
                BotConfig.walkerVerticalResync = wvr;
                applied.add("walkerVerticalResync");
            }
            if (params.get("walkerLevelRiserJump") instanceof Boolean wlrj) {
                BotConfig.walkerLevelRiserJump = wlrj;
                applied.add("walkerLevelRiserJump");
            }
            if (params.get("walkerPadRamBreak") instanceof Boolean wprb) {
                BotConfig.walkerPadRamBreak = wprb;
                applied.add("walkerPadRamBreak");
            }
            if (params.get("walkerParkourAscendHold") instanceof Boolean wpah) {
                BotConfig.walkerParkourAscendHold = wpah;
                applied.add("walkerParkourAscendHold");
            }
            if (params.get("walkerDeepWaterDriftBrake") instanceof Boolean wdwd) {
                BotConfig.walkerDeepWaterDriftBrake = wdwd;
                applied.add("walkerDeepWaterDriftBrake");
            }
            if (params.get("walkerSteepDescentLatch") instanceof Boolean wsdl) {
                BotConfig.walkerSteepDescentLatch = wsdl;
                applied.add("walkerSteepDescentLatch");
            }
            if (params.get("craftReclaimTable") instanceof Boolean crt) {
                BotConfig.craftReclaimTable = crt;
                applied.add("craftReclaimTable");
            }
            if (params.get("walkerDescentStepSkipBrake") instanceof Boolean wdssb) {
                BotConfig.walkerDescentStepSkipBrake = wdssb;
                applied.add("walkerDescentStepSkipBrake");
            }
            if (params.get("walkerDescentFlipHold") instanceof Boolean wdfh) {
                BotConfig.walkerDescentFlipHold = wdfh;
                applied.add("walkerDescentFlipHold");
            }
            if (params.get("walkerWaterStepDownFloat") instanceof Boolean wwsf) {
                BotConfig.walkerWaterStepDownFloat = wwsf;
                applied.add("walkerWaterStepDownFloat");
            }
            if (params.get("walkerStepUpCrestReach") instanceof Boolean wscr) {
                BotConfig.walkerStepUpCrestReach = wscr;
                applied.add("walkerStepUpCrestReach");
            }
            if (params.get("walkerWaterWalkReach") instanceof Boolean wwwr) {
                BotConfig.walkerWaterWalkReach = wwwr;
                applied.add("walkerWaterWalkReach");
            }
            if (params.get("walkerAscentRamJitterImmune") instanceof Boolean warji) {
                BotConfig.walkerAscentRamJitterImmune = warji;
                applied.add("walkerAscentRamJitterImmune");
            }
            if (params.get("walkerAscendMovement") instanceof Boolean wam) {
                BotConfig.walkerAscendMovement = wam;
                applied.add("walkerAscendMovement");
            }
            if (params.get("walkerArcLengthShadow") instanceof Boolean wals) {
                BotConfig.walkerArcLengthShadow = wals;
                applied.add("walkerArcLengthShadow");
            }
            if (params.get("walkerArcLengthAdvance") instanceof Boolean wala) {
                BotConfig.walkerArcLengthAdvance = wala;
                applied.add("walkerArcLengthAdvance");
            }
            if (params.get("walkerTangentAim") instanceof Boolean wta) {
                BotConfig.walkerTangentAim = wta;
                applied.add("walkerTangentAim");
            }
            if (params.get("walkerArcLengthWedge") instanceof Boolean walw) {
                BotConfig.walkerArcLengthWedge = walw;
                applied.add("walkerArcLengthWedge");
            }
            if (params.get("walkerArcProgressWedge") instanceof Boolean wapw) {
                BotConfig.walkerArcProgressWedge = wapw;
                applied.add("walkerArcProgressWedge");
            }
            if (params.get("walkerFellBelowAlign") instanceof Boolean wfba) {
                BotConfig.walkerFellBelowAlign = wfba;
                applied.add("walkerFellBelowAlign");
            }
            if (params.get("walkerAscentRamBobBreak") instanceof Boolean warb) {
                BotConfig.walkerAscentRamBobBreak = warb;
                applied.add("walkerAscentRamBobBreak");
            }
            if (params.get("walkerFutileBankDigRelease") instanceof Boolean wfbd) {
                BotConfig.walkerFutileBankDigRelease = wfbd;
                applied.add("walkerFutileBankDigRelease");
            }
            if (params.get("walkerBankDigSkipOverhang") instanceof Boolean wbdso) {
                BotConfig.walkerBankDigSkipOverhang = wbdso;
                applied.add("walkerBankDigSkipOverhang");
            }
            if (params.get("walkerBuoyantSearchFromSurface") instanceof Boolean wbsfs) {
                BotConfig.walkerBuoyantSearchFromSurface = wbsfs;
                applied.add("walkerBuoyantSearchFromSurface");
            }
            if (params.get("walkerBankDigForwardExit") instanceof Boolean wbdfe) {
                BotConfig.walkerBankDigForwardExit = wbdfe;
                applied.add("walkerBankDigForwardExit");
            }
            if (params.get("walkerPillarReachGoalNoSnap") instanceof Boolean wprgns) {
                BotConfig.walkerPillarReachGoalNoSnap = wprgns;
                applied.add("walkerPillarReachGoalNoSnap");
            }
            if (params.get("walkerBankDigSkipWhenCwpSwims") instanceof Boolean wbdscs) {
                BotConfig.walkerBankDigSkipWhenCwpSwims = wbdscs;
                applied.add("walkerBankDigSkipWhenCwpSwims");
            }
            if (params.get("walkerTraverseBreakOvershootResync") instanceof Boolean wtbor) {
                BotConfig.walkerTraverseBreakOvershootResync = wtbor;
                applied.add("walkerTraverseBreakOvershootResync");
            }
            if (params.get("walkerSwimAshorePillarDespiteDeepDig") instanceof Boolean wsapddd) {
                BotConfig.walkerSwimAshorePillarDespiteDeepDig = wsapddd;
                applied.add("walkerSwimAshorePillarDespiteDeepDig");
            }
            if (params.get("walkerFloatingBankBobFreeze") instanceof Boolean wfbbf) {
                BotConfig.walkerFloatingBankBobFreeze = wfbbf;
                applied.add("walkerFloatingBankBobFreeze");
            }
            if (params.get("walkerFloatingBankFollow") instanceof Boolean wfbf) {
                BotConfig.walkerFloatingBankFollow = wfbf;
                applied.add("walkerFloatingBankFollow");
            }
            if (params.get("walkerFasterChurnRepath") instanceof Boolean wfcr) {
                BotConfig.walkerFasterChurnRepath = wfcr;
                applied.add("walkerFasterChurnRepath");
            }
            if (params.get("walkerDeepWaterFloatBeeline") instanceof Boolean wdfb) {
                BotConfig.walkerDeepWaterFloatBeeline = wdfb;
                applied.add("walkerDeepWaterFloatBeeline");
            }
            if (params.get("pathfinderForbidParkourIntoDeepWater") instanceof Boolean fpdw) {
                BotConfig.pathfinderForbidParkourIntoDeepWater = fpdw;
                applied.add("pathfinderForbidParkourIntoDeepWater");
            }
            if (params.get("pathfinderForbidParkourFromFloatingWater") instanceof Boolean fpfw) {
                BotConfig.pathfinderForbidParkourFromFloatingWater = fpfw;
                applied.add("pathfinderForbidParkourFromFloatingWater");
            }
            if (params.get("pathfinderForbidParkourOverWaterGap") instanceof Boolean fpowg) {
                BotConfig.pathfinderForbidParkourOverWaterGap = fpowg;
                applied.add("pathfinderForbidParkourOverWaterGap");
            }
            if (params.get("pathfinderParkourAscendNeedRunway") instanceof Boolean ppanr) {
                BotConfig.pathfinderParkourAscendNeedRunway = ppanr;
                applied.add("pathfinderParkourAscendNeedRunway");
            }
            if (params.get("pathfinderFloatingSurfaceCross") instanceof Boolean pfsc) {
                BotConfig.pathfinderFloatingSurfaceCross = pfsc;
                applied.add("pathfinderFloatingSurfaceCross");
            }
            if (params.get("pathfinderVineOverWaterTax") instanceof Boolean pvow) {
                BotConfig.pathfinderVineOverWaterTax = pvow;
                applied.add("pathfinderVineOverWaterTax");
            }
            if (params.get("pathfinderPadOverWaterTax") instanceof Boolean ppow) {
                BotConfig.pathfinderPadOverWaterTax = ppow;
                applied.add("pathfinderPadOverWaterTax");
            }
            if (params.get("pathfinderPadClusterTax") instanceof Boolean ppct) {
                BotConfig.pathfinderPadClusterTax = ppct;
                applied.add("pathfinderPadClusterTax");
            }
            if (params.get("walkerVineFreeHangClimb") instanceof Boolean vfh) {
                BotConfig.walkerVineFreeHangClimb = vfh;
                applied.add("walkerVineFreeHangClimb");
            }
            if (params.get("walkerVineLandGrab") instanceof Boolean vlg) {
                BotConfig.walkerVineLandGrab = vlg;
                applied.add("walkerVineLandGrab");
            }
            if (params.get("walkerVineDescentDrop") instanceof Boolean vdd) {
                BotConfig.walkerVineDescentDrop = vdd;
                applied.add("walkerVineDescentDrop");
            }
            if (params.get("pathDebug") instanceof Boolean pd) {
                BotConfig.pathDebug = pd;
                applied.add("pathDebug");
            }
            if (params.get("pathArchive") instanceof Boolean pa) {
                BotConfig.pathArchive = pa;
                applied.add("pathArchive");
            }
            if (params.get("pathfinderCacheEnabled") instanceof Boolean pce) {
                BotConfig.pathfinderCacheEnabled = pce;
                applied.add("pathfinderCacheEnabled");
            }
            if (params.get("collisionAwarePathing") instanceof Boolean cap) {
                BotConfig.collisionAwarePathing = cap;
                applied.add("collisionAwarePathing");
            }
            if (params.get("pathfinderGoalField") instanceof Boolean gf) {
                BotConfig.pathfinderGoalField = gf;
                applied.add("pathfinderGoalField");
            }
            if (params.get("goalFieldCellSize") instanceof Number gfc) {
                BotConfig.goalFieldCellSize = Math.max(1, gfc.intValue());
                applied.add("goalFieldCellSize");
            }
            if (params.get("goalFieldRadius") instanceof Number gfr) {
                BotConfig.goalFieldRadius = Math.max(8, gfr.intValue());
                applied.add("goalFieldRadius");
            }
            if (params.get("goalFieldVerticalRadius") instanceof Number gfvr) {
                BotConfig.goalFieldVerticalRadius = Math.max(4, gfvr.intValue());
                applied.add("goalFieldVerticalRadius");
            }
            if (params.get("pathfinderDepthPenalty") instanceof Number dpp) {
                BotConfig.pathfinderDepthPenalty = Math.max(0, dpp.doubleValue());
                applied.add("pathfinderDepthPenalty");
            }
            if (params.get("pathfinderDepthSlack") instanceof Number dps) {
                BotConfig.pathfinderDepthSlack = Math.max(0, dps.intValue());
                applied.add("pathfinderDepthSlack");
            }
            if (params.get("pathfinderDescendCost") instanceof Number pdc) {
                BotConfig.pathfinderDescendCost = Math.max(0, pdc.doubleValue());
                applied.add("pathfinderDescendCost");
            }
            if (params.get("pathfinderWaterCellCost") instanceof Number pwc) {
                BotConfig.pathfinderWaterCellCost = Math.max(0, pwc.doubleValue());
                applied.add("pathfinderWaterCellCost");
            }
            if (params.get("pathfinderWaterClimbOutCost") instanceof Number pwco) {
                BotConfig.pathfinderWaterClimbOutCost = Math.max(0, pwco.doubleValue());
                applied.add("pathfinderWaterClimbOutCost");
            }
            if (params.get("pathfinderSubmergedWaterCost") instanceof Number pswc) {
                BotConfig.pathfinderSubmergedWaterCost = Math.max(0, pswc.doubleValue());
                applied.add("pathfinderSubmergedWaterCost");
            }
            if (params.get("pathfinderBridgeCost") instanceof Number pbc) {
                BotConfig.pathfinderBridgeCost = Math.max(0, pbc.doubleValue());
                applied.add("pathfinderBridgeCost");
            }
            if (params.get("pathfinderThinObstacleHeight") instanceof Number ptoh) {
                BotConfig.pathfinderThinObstacleHeight = Math.max(0, ptoh.doubleValue());
                applied.add("pathfinderThinObstacleHeight");
            }
            if (params.get("pathfinderFrontierCommit") instanceof Boolean fc) {
                BotConfig.pathfinderFrontierCommit = fc;
                applied.add("pathfinderFrontierCommit");
            }
            if (params.get("pathfinderProgressive") instanceof Boolean pp) {
                BotConfig.pathfinderProgressive = pp;
                applied.add("pathfinderProgressive");
            }
            if (params.get("pathfinderHorizonBlocks") instanceof Number phb) {
                int v = phb.intValue();
                if (v >= 0 && v <= 512) { BotConfig.pathfinderHorizonBlocks = v; applied.add("pathfinderHorizonBlocks"); }
                else rejected.add("pathfinderHorizonBlocks out of range [0,512]");
            }
            if (params.get("pathfinderMaxDryFall") instanceof Number pmdf) {
                int v = pmdf.intValue();
                if (v >= 3 && v <= 5) { BotConfig.pathfinderMaxDryFall = v; applied.add("pathfinderMaxDryFall"); }
                else rejected.add("pathfinderMaxDryFall out of range [3,5]");
            }
            if (params.get("pathfinderSoftCommitNodes") instanceof Number pscn) {
                int v = pscn.intValue();
                if (v >= 0 && v <= 1_000_000) { BotConfig.pathfinderSoftCommitNodes = v; applied.add("pathfinderSoftCommitNodes"); }
                else rejected.add("pathfinderSoftCommitNodes out of range [0,1000000]");
            }
            if (params.get("pathfinderQuickNodes") instanceof Number pqn) {
                int v = pqn.intValue();
                if (v >= 0 && v <= 10_000) { BotConfig.pathfinderQuickNodes = v; applied.add("pathfinderQuickNodes"); }
                else rejected.add("pathfinderQuickNodes out of range [0,10000]");
            }
            if (params.get("pathChartAutoDump") instanceof Boolean pcad) {
                BotConfig.pathChartAutoDump = pcad;
                applied.add("pathChartAutoDump");
            }
            if (params.get("elytraDebug") instanceof Boolean ed) {
                BotConfig.elytraDebug = ed;
                applied.add("elytraDebug");
            }
            if (params.get("debugFly") instanceof Boolean dfly) {
                // Test affordance: toggle creative flight on the CLIENT thread
                // (a real Java Runnable — the Rhino sandbox blocks this from
                // eval). Lets the flight-handling path be exercised live.
                Minecraft mcf = Minecraft.getInstance();
                mcf.execute(() -> {
                    LocalPlayer pf = mcf.player;
                    if (pf == null) {
                        LOG.info("[debugFly] player null");
                        return;
                    }
                    pf.getAbilities().mayfly = true;
                    pf.getAbilities().flying = dfly;
                    pf.onUpdateAbilities();
                    LOG.info(
                            "[debugFly] requested={} readback mayfly={} flying={} onGround={} y={}",
                            dfly, pf.getAbilities().mayfly, pf.getAbilities().flying, pf.onGround(), pf.getY());
                });
                applied.add("debugFly");
            }
            if (params.get("pathfinder.mobAvoidRadius") instanceof Number mr) {
                double v = mr.doubleValue();
                if (v < 0 || v > 64) {
                    rejected.add("pathfinder.mobAvoidRadius: out of range [0,64]");
                } else {
                    BotConfig.mobAvoidRadius = v;
                    applied.add("pathfinder.mobAvoidRadius");
                }
            }
            if (params.get("pathfinder.avoidZonePenalty") instanceof Number azp) {
                double v = azp.doubleValue();
                if (v < 0 || v > 5000) {
                    rejected.add("pathfinder.avoidZonePenalty: out of range [0,5000]");
                } else {
                    BotConfig.avoidZonePenalty = v;
                    applied.add("pathfinder.avoidZonePenalty");
                }
            }
            if (params.get("pathfinder.mobAvoidPenalty") instanceof Number mp) {
                double v = mp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.mobAvoidPenalty: out of range [0,1000]");
                } else {
                    BotConfig.mobAvoidPenalty = v;
                    applied.add("pathfinder.mobAvoidPenalty");
                }
            }
            if (params.get("rangedAvoidRadius") instanceof Number rar) {
                int v = rar.intValue();
                if (v < 4 || v > 48) {
                    rejected.add("rangedAvoidRadius: out of range [4,48]");
                } else {
                    BotConfig.rangedAvoidRadius = v;
                    applied.add("rangedAvoidRadius");
                }
            }
            if (params.get("fleeDangerBoost") instanceof Number fdb) {
                double v = fdb.doubleValue();
                if (v < 1 || v > 20) {
                    rejected.add("fleeDangerBoost: out of range [1,20]");
                } else {
                    BotConfig.fleeDangerBoost = v;
                    applied.add("fleeDangerBoost");
                }
            }
            if (params.get("smoothLook") instanceof Boolean sl) {
                BotConfig.smoothLook = sl;
                applied.add("smoothLook");
            }
            if (params.get("autoBackfill") instanceof Boolean abf) {
                BotConfig.autoBackfill = abf;
                if (!abf) bot.backfillTracker.clear();
                applied.add("autoBackfill");
            }
            if (params.get("autoBackfillBlock") instanceof String abb && !abb.isBlank()) {
                try {
                    var rl = ResourceLocation.parse(abb);
                    if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
                        rejected.add("autoBackfillBlock: unknown block id " + abb);
                    } else {
                        BotConfig.autoBackfillBlock = rl.toString();
                        applied.add("autoBackfillBlock");
                    }
                } catch (Exception ex) {
                    rejected.add("autoBackfillBlock: invalid id " + abb);
                }
            }
            // blocksToAvoid is a list of block ids; each must parse as a
            // ResourceLocation (e.g. "minecraft:powder_snow"). Whole-list
            // replacement — pass [] to clear. Invalid ids cause the entire
            // write to be rejected so the caller knows nothing was applied.
            if (params.get("blocksToAvoid") instanceof List<?> bl) {
                List<String> bad = new ArrayList<>();
                Set<String> nextSet = new LinkedHashSet<>();
                for (Object o : bl) {
                    if (!(o instanceof String s) || s.isBlank()) { bad.add(String.valueOf(o)); continue; }
                    try {
                        ResourceLocation rl = ResourceLocation.parse(s);
                        if (!BuiltInRegistries.BLOCK.containsKey(rl)) { bad.add(s); continue; }
                        nextSet.add(rl.toString());
                    } catch (Exception e) { bad.add(s); }
                }
                if (bad.isEmpty()) {
                    BotConfig.extraHazardBlocks = Set.copyOf(nextSet);
                    applied.add("blocksToAvoid");
                } else {
                    rejected.add("blocksToAvoid: invalid ids " + bad);
                }
            }
            // buildBlockWhitelist is a list of block ids the bot may PLACE as build/support
            // blocks (pillar/bridge/parkour footing). Same ResourceLocation validation as
            // blocksToAvoid; whole-list replacement, pass [] to clear (→ falls back to the
            // full-cube heuristic). Invalid ids reject the entire write.
            if (params.get("buildBlockWhitelist") instanceof List<?> wl) {
                List<String> bad = new ArrayList<>();
                Set<String> nextSet = new LinkedHashSet<>();
                for (Object o : wl) {
                    if (!(o instanceof String s) || s.isBlank()) { bad.add(String.valueOf(o)); continue; }
                    try {
                        ResourceLocation rl = ResourceLocation.parse(s);
                        if (!BuiltInRegistries.BLOCK.containsKey(rl)) { bad.add(s); continue; }
                        nextSet.add(rl.toString());
                    } catch (Exception e) { bad.add(s); }
                }
                if (bad.isEmpty()) {
                    BotConfig.buildBlockWhitelist = Set.copyOf(nextSet);
                    applied.add("buildBlockWhitelist");
                } else {
                    rejected.add("buildBlockWhitelist: invalid ids " + bad);
                }
            }
            // mutedEvents: event types suppressed from the live PUSH channel (the event
            // is still recorded + retrievable via mc.wait.event / replay). Free-form
            // strings — any event type. Whole-list replace; pass [] to un-mute everything.
            if (params.get("mutedEvents") instanceof List<?> ml) {
                Set<String> nextSet = new LinkedHashSet<>();
                for (Object o : ml) {
                    if (o instanceof String s && !s.isBlank()) nextSet.add(s);
                }
                BotConfig.mutedEvents = Set.copyOf(nextSet);
                applied.add("mutedEvents");
            }
            // avoidPoints: agent-marked danger zones to route around. List of points,
            // each {x,y,z,radius?} (radius default 8). Whole-list replacement — pass []
            // to clear. e.g. [{"x":50,"y":58,"z":350,"radius":10}].
            if (params.get("avoidPoints") instanceof List<?> ap) {
                List<double[]> zones = new ArrayList<>();
                boolean ok = true;
                for (Object o : ap) {
                    if (!(o instanceof Map<?, ?> pm)) { ok = false; break; }
                    Object x = pm.get("x"), y = pm.get("y"), z = pm.get("z"), r = pm.get("radius");
                    if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) { ok = false; break; }
                    double rad = (r instanceof Number nr) ? nr.doubleValue() : 8.0;
                    if (rad < 1) rad = 1; if (rad > 64) rad = 64;
                    zones.add(new double[]{ nx.doubleValue(), ny.doubleValue(), nz.doubleValue(), rad });
                }
                if (ok) {
                    BotConfig.avoidZones = zones.toArray(new double[0][]);
                    applied.add("avoidPoints");
                } else {
                    rejected.add("avoidPoints: each item must be {x,y,z,radius?}");
                }
            }
            SettingsNumericWrites.apply(params, applied, rejected);
        }
        // Generic reflective fallback for every key the hand-written setters above did not
        // consume: any `public static volatile` PRIMITIVE field on BotConfig is settable by
        // its exact field name. This ends the copy-paste growth of this class — a new flag
        // needs ONLY its BotConfig declaration (the ~96 boolean setters above predate this
        // and are kept for their side effects / legacy key aliases; do not add more).
        // volatile is required (marker that the field is designed for runtime flips);
        // range-validated knobs keep their hand-written setters above, which win by running
        // first and adding the key to `applied`.
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String k = e.getKey();
            if (applied.contains(k) || rejected.stream().anyMatch(r -> r.startsWith(k))) continue;
            try {
                java.lang.reflect.Field f = BotConfig.class.getField(k);
                int mods = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isVolatile(mods)) continue;
                Class<?> t = f.getType();
                Object v = e.getValue();
                if (t == boolean.class && v instanceof Boolean b) { f.setBoolean(null, b); applied.add(k); }
                else if (t == int.class && v instanceof Number n) { f.setInt(null, n.intValue()); applied.add(k); }
                else if (t == double.class && v instanceof Number n) { f.setDouble(null, n.doubleValue()); applied.add(k); }
                else if (t == float.class && v instanceof Number n) { f.setFloat(null, n.floatValue()); applied.add(k); }
                else if (t == long.class && v instanceof Number n) { f.setLong(null, n.longValue()); applied.add(k); }
            } catch (NoSuchFieldException | IllegalAccessException ignore) {
                // unknown key → ignored, same contract as before
            }
        }
        // Runtime apply-keys ⊆ registry self-check: a param key that is KNOWN to the registry yet
        // was matched by NO branch above (not applied, not rejected) is INERT — a registry key with
        // no live write path (e.g. a hand-written setter that expects a different value type than
        // the schema advertised, or a reflected key whose reflective write silently no-op'd). It is
        // NOT an unknown key (those already threw), so we surface it honestly rather than drop it.
        // In a correct build this list is empty; it fires only on real drift and is logged loudly.
        List<String> inert = new ArrayList<>();
        if (params != null) {
            for (String k : params.keySet()) {
                if (applied.contains(k)) continue;
                if (rejected.stream().anyMatch(r -> r.startsWith(k))) continue;
                inert.add(k);   // known (unknown keys threw earlier) but no branch consumed it
            }
        }
        if (!inert.isEmpty()) {
            LOG.warn("[mc.bot.setting] inert key(s) {} — known to SettingsRegistry but matched no "
                    + "apply branch (drift: a registry key lost its write path — or a direct in-JVM caller passed a wrong-typed value, which bypasses the route-layer validator)", inert);
        }
        Map<String, Object> snap = SettingsSnapshot.build(bot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", snap);
        if (!applied.isEmpty()) {
            out.put("applied", applied);
            BotConfig.save();   // persist so these settings survive a client restart
        }
        if (!rejected.isEmpty()) out.put("rejected", rejected);
        if (!inert.isEmpty()) out.put("inert", inert);
        return out;
    }
}
