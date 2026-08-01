package net.magicterra.worlddriver.bot.world;

/** One cell of the HazardField grid. Immutable value. */
public record HazardCell(
        int cliffDropDepth,   // blocks of air below the standable foot (0 = solid right under)
        int deepWaterDepth,   // water column depth at this cell (0 = not water)
        boolean contactDamage,// lava/fire/cactus/magma/berry/powder-snow at foot or head
        boolean standable,    // a 2-tall passable space with support
        boolean lethal        // computed: would entering/standing here likely kill us now
) {
    public static HazardCell unknown() {
        return new HazardCell(0, 0, false, false, false);
    }
}
