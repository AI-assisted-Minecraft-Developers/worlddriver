package net.magicterra.worlddriver.bot.auto;

import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.bot.util.BotInteract.ensureHolding;
import static net.magicterra.worlddriver.bot.util.BotInteract.hotbarSlotOf;

/**
 * Phase B reflex — raise a shield against an incoming projectile or a meleeing
 * mob that's facing us, turning to face the threat (a shield only blocks from the
 * direction you look). Contends for the use key; the host arbitrates shield over
 * heal over eat. Creepers are NOT shielded (the blast goes through) — those are
 * the PanicChain's job, so this skips them.
 *
 * <p>Holds the use key while raised; a shield in the offhand raises even with a
 * weapon in the main hand, otherwise we select a hotbar shield first.
 */
public final class AutoShield {
    private boolean raising;

    /** True when there's something worth blocking AND a shield to block with. */
    public boolean wants(Minecraft mc, LocalPlayer p, ThreatScanner.Scan scan) {
        if (!hasShield(p)) return false;
        return focus(p, scan) != null;
    }

    /** Pick the thing to face: a projectile predicted to hit, else a facing melee
     *  mob within reach. Returns its position, or null if nothing to block. */
    private Vec3 focus(LocalPlayer p, ThreatScanner.Scan scan) {
        for (ThreatScanner.Incoming in : scan.projectiles()) {
            if (in.willHit()) return in.pos();
        }
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.creeperSwell() > 0) continue;            // panic handles creepers
            if (t.distance() <= 3.5 && t.facingMe()) return t.entity().position();
        }
        return null;
    }

    public void engage(Minecraft mc, LocalPlayer p, ThreatScanner.Scan scan) {
        Vec3 at = focus(p, scan);
        if (at != null) faceTowards(p, at);
        // Shield in offhand raises regardless of main hand; otherwise hold one.
        if (!p.getInventory().offhand.get(0).is(Items.SHIELD)) {
            ensureHolding(mc, Items.SHIELD);
        }
        mc.options.keyUse.setDown(true);
        raising = true;
    }

    public void release(Minecraft mc) {
        if (raising) {
            mc.options.keyUse.setDown(false);
            raising = false;
        }
    }

    private boolean hasShield(LocalPlayer p) {
        return p.getInventory().offhand.get(0).is(Items.SHIELD)
                || hotbarSlotOf(p, Items.SHIELD) >= 0;
    }

    private void faceTowards(LocalPlayer p, Vec3 at) {
        double dx = at.x - p.getX();
        double dy = at.y - (p.getY() + p.getEyeHeight());
        double dz = at.z - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horiz))));
    }
}
