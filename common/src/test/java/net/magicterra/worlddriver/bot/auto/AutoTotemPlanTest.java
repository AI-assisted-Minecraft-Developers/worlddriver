package net.magicterra.worlddriver.bot.auto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import net.magicterra.worlddriver.bot.auto.AutoTotem.Click;
import net.magicterra.worlddriver.bot.auto.AutoTotem.Held;
import org.junit.jupiter.api.Test;

/**
 * The offhand swap sends whatever the offhand held into the slot the totem came from. AutoShield
 * only looks at the offhand and the hotbar, so a shield that lands in the backpack is a shield the
 * bot never raises again. These tests replay the clicks against a model of the menu and ask where
 * the shield ends up.
 */
class AutoTotemPlanTest {

    private static final int SELECTED = 0;

    /** Backpack and hotbar all holding something unremarkable. */
    private static Held[] fullMenu() {
        Held[] m = new Held[AutoTotem.LAST_HOTBAR + 1];
        Arrays.fill(m, Held.OTHER);
        return m;
    }

    /** A menu plus the offhand, with vanilla's SWAP semantics. */
    private static final class Inventory {
        final Held[] menu;
        Held offhand;
        Inventory(Held offhand, Held[] menu) { this.offhand = offhand; this.menu = menu; }

        void apply(Click c) {
            Held clicked = menu[c.slot()];
            if (c.button() == AutoTotem.OFFHAND_BUTTON) {
                menu[c.slot()] = offhand;
                offhand = clicked;
            } else {
                int hotbar = AutoTotem.FIRST_HOTBAR + c.button();
                menu[c.slot()] = menu[hotbar];
                menu[hotbar] = clicked;
            }
        }

        /** Runs the reflex tick after tick until it has nothing left to do. */
        void settle() {
            for (int i = 0; i < 4; i++) {
                Click c = AutoTotem.plan(offhand, menu, SELECTED);
                if (c == null) return;
                apply(c);
            }
            throw new AssertionError("the reflex kept clicking without settling");
        }

        boolean shieldOnHotbar() {
            for (int i = AutoTotem.FIRST_HOTBAR; i <= AutoTotem.LAST_HOTBAR; i++) if (menu[i] == Held.SHIELD) return true;
            return false;
        }
    }

    @Test
    void aBackpackTotemNeverPushesAnOffhandShieldIntoTheBackpack() {
        Held[] menu = fullMenu();
        menu[20] = Held.TOTEM;
        menu[AutoTotem.FIRST_HOTBAR + 3] = Held.EMPTY;
        Inventory inv = new Inventory(Held.SHIELD, menu);
        inv.settle();
        assertEquals(Held.TOTEM, inv.offhand);
        assertTrue(inv.shieldOnHotbar(), "the displaced shield must stay where AutoShield looks: " + Arrays.toString(inv.menu));
    }

    @Test
    void aShieldStaysReachableEvenWithNoEmptyHotbarSlot() {
        Held[] menu = fullMenu();
        menu[20] = Held.TOTEM;
        Inventory inv = new Inventory(Held.SHIELD, menu);
        inv.settle();
        assertEquals(Held.TOTEM, inv.offhand);
        assertTrue(inv.shieldOnHotbar(), Arrays.toString(inv.menu));
        assertEquals(Held.OTHER, inv.menu[AutoTotem.FIRST_HOTBAR + SELECTED],
                "the main hand's item is not the one to push into the backpack");
    }

    @Test
    void aHotbarTotemIsPreferredOverABackpackOne() {
        Held[] menu = fullMenu();
        menu[20] = Held.TOTEM;
        menu[AutoTotem.FIRST_HOTBAR + 5] = Held.TOTEM;
        Click c = AutoTotem.plan(Held.SHIELD, menu, SELECTED);
        assertEquals(new Click(AutoTotem.FIRST_HOTBAR + 5, AutoTotem.OFFHAND_BUTTON), c);
    }

    @Test
    void withNoShieldInTheOffhandABackpackTotemGoesStraightIn() {
        Held[] menu = fullMenu();
        menu[20] = Held.TOTEM;
        assertEquals(new Click(20, AutoTotem.OFFHAND_BUTTON), AutoTotem.plan(Held.EMPTY, menu, SELECTED));
        assertEquals(new Click(20, AutoTotem.OFFHAND_BUTTON), AutoTotem.plan(Held.OTHER, menu, SELECTED));
    }

    @Test
    void aTotemAlreadyInTheOffhandOrNoneAnywhereMeansNoClick() {
        Held[] menu = fullMenu();
        menu[20] = Held.TOTEM;
        assertNull(AutoTotem.plan(Held.TOTEM, menu, SELECTED));
        assertNull(AutoTotem.plan(Held.SHIELD, fullMenu(), SELECTED));
    }

    @Test
    void aHotbarOfShieldsTakesTheTotemStraightIntoTheOffhand() {
        Held[] menu = fullMenu();
        menu[20] = Held.TOTEM;
        for (int i = AutoTotem.FIRST_HOTBAR; i <= AutoTotem.LAST_HOTBAR; i++) menu[i] = Held.SHIELD;
        menu[AutoTotem.FIRST_HOTBAR + SELECTED] = Held.OTHER;
        Inventory inv = new Inventory(Held.SHIELD, menu);
        inv.settle();
        assertEquals(Held.TOTEM, inv.offhand);
        assertTrue(inv.shieldOnHotbar());
    }

    @Test
    void aHotbarShieldIsNotTheSlotATotemIsParkedIn() {
        Held[] menu = fullMenu();
        menu[20] = Held.TOTEM;
        menu[AutoTotem.FIRST_HOTBAR + 1] = Held.SHIELD;
        Click c = AutoTotem.plan(Held.SHIELD, menu, SELECTED);
        assertNotEquals(1, c.button(), "parking the totem there would push the spare shield into the backpack");
    }
}
