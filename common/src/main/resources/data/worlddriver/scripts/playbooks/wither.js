// ROADMAP Phase G — Wither playbook. Rhino script (hot-reloadable; see
// docs/design/boss-playbooks.md). The Wither is gear- and arena-gated, so the
// playbook hard-checks Phase F gear up front and aborts back to the planner if it
// is not ready. It then (optionally) summons in place, backs off the spawn
// explosion, and melee-grinds both phases while T0 reflexes handle skull dodging
// and survival.
//
// Summoning is gated behind PLAYBOOK.summon (default true) so a no-arg dry-run just
// validates the gear gate and exits — handy for "am I ready for a wither yet?".
// Notes for live tuning: vanilla phase 2 (powered, <=50% hp) is immune to
// projectiles and burrows, so this melees throughout; a sealed bedrock box keeps it
// from escaping (build with mc.action.placeMany/fill before calling).
(function () {
    function boss()     { return Driver.invoke('mc.observe.boss', { radius: 48 }); }
    function player()   { return Driver.invoke('mc.observe.player', {}).player; }
    function aborting()  { return Driver.invoke('mc.bot.playbook', { op: 'status' }).stopping; }

    var summon = !(typeof PLAYBOOK !== 'undefined' && PLAYBOOK.summon === false);

    // --- Gear gate (Phase F): equip the best we have, then verify all four armour
    //     slots are filled and a sword is in hand; abort to the planner otherwise.
    try { Driver.invoke('mc.bot.equip', { profile: 'best' }); } catch (e) {}
    var me = player();
    var gear = me ? me.equipment : { armor: {} };
    var missing = [];
    ['head', 'chest', 'legs', 'feet'].forEach(function (s) {
        if (!gear.armor[s]) missing.push(s);
    });
    var hasSword = !!(gear.mainHand && gear.mainHand.id.indexOf('sword') >= 0);
    if (missing.length > 0 || !hasSword) {
        return { ok: false, note: 'gear check failed — need full armor + a sword before summoning a wither',
                 missingArmor: missing, hasSword: hasSword };
    }

    Driver.bot.setting({ op: 'patch', values: { autoTotem: true, autoHeal: true, autoEat: true, autoDodge: true, autoRetreat: false } });

    // --- Summon (optional). The wither stays invulnerable ~220 ticks, then
    //     detonates a large explosion — back off and let it blow before closing in.
    if (summon) {
        if (!boss().boss) {
            var p = player();
            if (p) {
                var sx = Math.floor(p.pos.x) + 4, sy = Math.floor(p.pos.y), sz = Math.floor(p.pos.z);
                Driver.invoke('mc.action.runCommand', { cmd: 'summon minecraft:wither ' + sx + ' ' + sy + ' ' + sz });
            }
            Driver.system.waitTicks({ ticks: 10 });
        }
    }

    var b = boss().boss;
    if (!b || b.type !== 'wither') {
        return { ok: true, note: summon ? 'summon failed or no wither present' : 'no wither present' };
    }

    var maxRounds = (typeof PLAYBOOK !== 'undefined' && PLAYBOOK.maxRounds) ? PLAYBOOK.maxRounds : 8000;
    var rounds = 0, backedOff = false;

    while (true) {
        if (aborting())           return { ok: false, note: 'aborted', rounds: rounds };
        if (++rounds > maxRounds) return { ok: false, note: 'maxRounds exceeded', rounds: rounds };

        b = boss().boss;
        if (!b)                   return { ok: true, note: 'wither defeated', rounds: rounds };

        if (b.invulTicks > 0) {
            // Spawning: stay clear of the imminent explosion.
            if (!backedOff) { try { Driver.invoke('mc.bot.runAway', { minDist: 12 }); } catch (e) {} backedOff = true; }
            Driver.system.waitTicks({ ticks: 10 });
            continue;
        }
        backedOff = false;

        // Both phases: melee the wither and chase. T0 autoDodge handles the skulls;
        // autoHeal/autoTotem cover the wither debuff's chip damage.
        Driver.invoke('mc.bot.combat', { mode: 'kill', target: { kind: 'entityType', type: 'minecraft:wither' } });
        Driver.system.waitTicks({ ticks: 10 });
    }
})();
