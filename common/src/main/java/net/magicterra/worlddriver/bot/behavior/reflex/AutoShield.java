package net.magicterra.worlddriver.bot.behavior.reflex;

import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.body.Hotbar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Phase B reflex — raise a shield against an incoming projectile or a meleeing mob that's facing us, turning to face
 * the threat (a shield only blocks from the direction you look). Contends for the use key; the host arbitrates shield
 * over heal over eat. Creepers are NOT shielded (the blast goes through) — those are the PanicChain's job, so this
 * skips them.
 *
 * <p>Holds the use intent while raised; a shield in the offhand raises even with a weapon in the main hand, otherwise
 * we select a hotbar shield first.
 */
public final class AutoShield {
    private boolean raising;

    /** True when there's something worth blocking AND a shield to block with. */
    public boolean wants(Body body, ThreatScanner.Scan scan) {
        Player p = body.asPlayer();
        if (p == null || body.equipment().isEmpty() || !hasShield(p)) {
            return false;
        }
        return focus(scan) != null;
    }

    /**
     * Pick the thing to face: a projectile predicted to hit, else a facing melee mob within reach. Returns its
     * position, or null if nothing to block.
     */
    private Vec3 focus(ThreatScanner.Scan scan) {
        for (ThreatScanner.Incoming in : scan.projectiles()) {
            if (in.willHit()) {
                return in.pos();
            }
        }
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.creeperSwell() > 0) {
                continue; // panic handles creepers
            }
            if (t.distance() <= 3.5 && t.facingMe()) {
                return t.entity().position();
            }
        }
        return null;
    }

    public void engage(Body body, ThreatScanner.Scan scan) {
        Player p = body.asPlayer();
        Equipment hands = body.equipment().orElse(null);
        if (p == null || hands == null) {
            return;
        }
        Vec3 at = focus(scan);
        if (at != null) {
            faceTowards(p, at);
        }
        // Shield in offhand raises regardless of main hand; otherwise hold one.
        if (!p.getInventory().offhand.get(0).is(Items.SHIELD)) {
            hands.holdItem(Items.SHIELD);
        }
        hands.commandUseItem(true);
        raising = true;
    }

    public void release(Body body) {
        if (raising) {
            body.equipment().ifPresent(h -> h.commandUseItem(false));
            raising = false;
        }
    }

    private boolean hasShield(Player p) {
        return p.getInventory().offhand.get(0).is(Items.SHIELD) || Hotbar.slotOf(p, Items.SHIELD) >= 0;
    }

    private void faceTowards(Player p, Vec3 at) {
        double dx = at.x - p.getX();
        double dy = at.y - (p.getY() + p.getEyeHeight());
        double dz = at.z - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horiz))));
    }
}
