// ROADMAP Phase G — Ender Dragon playbook. A Rhino script (NOT hardcoded Java) so
// it hot-reloads and the community can tune it — see docs/design/boss-playbooks.md.
// It orchestrates the existing primitives (combat / goto / equip / setting) plus the
// boss sensing verb (mc.observe.boss) into a multi-phase fight:
//
//   Phase 1 (HARD GATE): destroy every pillar End Crystal. While any remain the
//       dragon heals to full, so we never touch the dragon until they are gone.
//   Phase 2: perch  -> close on the head and melee (combat hits the dragon body,
//                      which is the resolvable damage path from a by-type target);
//            flying -> hold kill intent (ranged combat draws a held bow, else it
//                      lands hits as the dragon swoops). T0 reflexes
//                      (autoTotem/autoHeal/autoDodge) handle breath / charge dodging.
//
// Live-fight tuning (bow vs climb for caged crystals, bed-bombing the perch for the
// big single-hit damage) is the manual-smoke iteration surface — exactly why this is
// a hot-reloadable playbook and not Java. Returns a result envelope read back via
// mc.bot.playbook{op:'status'}.
(function () {
    var SENSE_RADIUS = 96;

    function boss()     { return Driver.invoke('mc.observe.boss', { radius: SENSE_RADIUS }); }
    function aborting()  { var s = Driver.invoke('mc.bot.playbook', { op: 'status' }); return !!(s && s.aborting); }
    function nearest(list) {
        var best = list[0];
        for (var i = 1; i < list.length; i++) if (list[i].distance < best.distance) best = list[i];
        return best;
    }

    var b = boss();
    if (!b || !b.present || b.type !== 'ender_dragon') {
        return { ok: true, note: 'no ender dragon present', crystals: (b && b.crystals ? b.crystals.length : 0) };
    }

    // T0 survival reflexes on for the whole fight.
    Driver.invoke('mc.bot.setting', { autoTotem: true, autoHeal: true, autoEat: true, autoDodge: true });
    // Gear up if we are carrying anything better (idempotent — no-op once best is worn).
    try { Driver.invoke('mc.bot.equip', { profile: 'best' }); } catch (e) {}

    var maxRounds = (typeof PLAYBOOK !== 'undefined' && PLAYBOOK.maxRounds) ? PLAYBOOK.maxRounds : 6000;
    var rounds = 0, crystalsCleared = false;

    while (true) {
        if (aborting())            return { ok: false, note: 'aborted', rounds: rounds, crystalsCleared: crystalsCleared };
        if (++rounds > maxRounds)  return { ok: false, note: 'maxRounds exceeded', rounds: rounds };

        b = boss();
        if (!b || !b.present)      return { ok: true, note: 'dragon defeated', rounds: rounds, crystalsCleared: crystalsCleared };

        var crystals = b.crystals || [];
        if (crystals.length > 0) {
            // Phase 1 — clear crystals (hard gate; the dragon heals while any live).
            var c = nearest(crystals);
            // Caged crystals sit in an iron-bar cage atop the taller pillars — get
            // up close (range 2) so we can break in; exposed ones we approach loosely.
            try {
                Driver.invoke('mc.bot.goto', { pos: { x: c.pos.x, y: c.pos.y, z: c.pos.z }, near: c.caged ? 2 : 4 });
            } catch (e) {}
            Driver.invoke('mc.bot.combat', { mode: 'kill', target: { id: c.id } });
            Driver.system.waitTicks(12);
            continue;
        }

        crystalsCleared = true;
        // Phase 2 — engage the dragon itself (target by type: combat resolves the
        // main EnderDragon entity, whose hurt() routes through the bot part).
        if (b.perched && b.head) {
            // Perch is the prime damage window — close on the head, then melee.
            try {
                Driver.invoke('mc.bot.goto', { pos: { x: Math.floor(b.head.x), y: Math.floor(b.head.y), z: Math.floor(b.head.z) }, near: 3 });
            } catch (e) {}
        }
        Driver.invoke('mc.bot.combat', { mode: 'kill', target: { type: 'ender_dragon' } });
        Driver.system.waitTicks(12);
    }
})();
