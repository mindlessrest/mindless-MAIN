/*
 * Mindless client sandbox.
 *
 * A playable stand-in for the game with the client's HUD and click GUI over it, so the parts that
 * are judged by eye can be judged against something moving. What has a counterpart in Java is a
 * transcription of it: the array list runs HUD.paintBackgroundShapes, and the effects use the
 * lifetimes and easing in mindless/effect. A radius or a duration settled here is the number to
 * put in the slider.
 *
 * The world is a real voxel grid with a real projection, which is what F5 and looking straight up
 * both need. It is not trying to be Minecraft; it is trying to be honest about scale, occlusion
 * and motion, because a HUD judged over a flat colour is not judged at all.
 */
(function (MS) {
    "use strict";

    var sandbox = MS.sandbox = {};

    var glCanvas = document.getElementById("world");
    var hudCanvas = document.getElementById("overlay");
    var hud = hudCanvas.getContext("2d");
    var stage = document.getElementById("stage");
    var curtain = document.getElementById("curtain");

    var W = 0, H = 0, DPR = 1;
    var gl = MS.GL.init(glCanvas);
    if (!gl) {
        curtain.innerHTML = "<h1>WebGL unavailable</h1><p>This sandbox needs WebGL. "
            + "Enable hardware acceleration and reload.</p>";
        return;
    }

    MS.HUD.attach(hud);
    MS.GUI.attach(hud);

    function resize() {
        DPR = Math.min(2, window.devicePixelRatio || 1);
        var r = stage.getBoundingClientRect();
        W = Math.max(480, Math.floor(r.width));
        H = Math.max(320, Math.floor(r.height));
        glCanvas.width = Math.floor(W * DPR);
        glCanvas.height = Math.floor(H * DPR);
        hudCanvas.width = Math.floor(W * DPR);
        hudCanvas.height = Math.floor(H * DPR);
        hud.setTransform(DPR, 0, 0, DPR, 0, 0);
        MS.HUD.size(W, H);
        MS.GUI.size(W, H);
    }
    window.addEventListener("resize", resize);

    /* --------------------------------------------------------------- settings */

    function S(label, type, value, extra) {
        var s = { label: label, type: type, value: value };
        if (type === "slider") { s.min = extra[0]; s.max = extra[1]; s.step = extra[2]; s.unit = extra[3] || ""; }
        if (type === "mode") { s.options = extra; }
        return s;
    }

    var modules = [
        { name: "HUD", cat: "Render", on: true, key: 0, settings: [
            S("Draw background", "toggle", true),
            S("Background mode", "mode", 0, ["Connected", "Per line", "Panel"]),
            S("Rounded background", "toggle", true),
            S("Corner radius", "slider", 4, [0, 20, 0.5, "px"]),
            S("Step rounding", "slider", 100, [0, 100, 5, "%"]),
            S("Row separators", "toggle", true),
            S("Background opacity", "slider", 43, [0, 100, 1, "%"]),
            S("Color mode", "mode", 2, ["Static", "Gradient", "Rainbow"]),
            S("Color", "color", [255, 79, 163]),
            S("Color 2", "color", [79, 216, 255]),
            S("Wave speed", "slider", 1, [0.1, 4, 0.1, "x"]),
            S("Font size", "slider", 1, [0.8, 1.6, 0.05, "x"]),
            S("Line spacing", "slider", 0, [0, 6, 1, "px"]),
            S("Text shadow", "toggle", true),
            S("Align right", "toggle", true),
            S("Lowercase", "toggle", false)
        ] },
        { name: "Click GUI", cat: "Client", on: true, key: 54, hidden: true, settings: [
            S("Accent", "color", [255, 79, 163]),
            S("Opacity", "slider", 94, [40, 100, 1, "%"]),
            S("Rounding", "slider", 8, [0, 16, 1, "px"]),
            S("Scale", "slider", 1, [0.8, 1.5, 0.05, "x"]),
            S("Dim background", "slider", 38, [0, 80, 1, "%"]),
            S("Border", "toggle", true)
        ] },
        { name: "Hit Effect", cat: "Render", on: true, key: 0, settings: [
            S("Mode", "mode", 0, ["Ripple", "Shockwave", "Both"]),
            S("Duration", "slider", 0.9, [0.2, 3, 0.05, "s"]),
            S("Radius", "slider", 3, [0.5, 8, 0.1, "b"]),
            S("Thickness", "slider", 0.18, [0.02, 1, 0.02, "b"]),
            S("Ripples", "slider", 3, [1, 6, 1, ""]),
            S("Sparks", "toggle", false),
            S("Spark count", "slider", 18, [4, 80, 1, ""]),
            S("Color", "color", [79, 216, 255])
        ] },
        { name: "Jump Effect", cat: "Render", on: true, key: 0, settings: [
            S("Mode", "mode", 0, ["Ring", "Dust", "Both"]),
            S("Duration", "slider", 0.7, [0.2, 2, 0.05, "s"]),
            S("Radius", "slider", 1.8, [0.4, 5, 0.1, "b"]),
            S("Thickness", "slider", 0.14, [0.02, 0.8, 0.02, "b"]),
            S("Dust count", "slider", 18, [4, 60, 1, ""]),
            S("Color", "color", [255, 79, 163])
        ] },
        { name: "Bind GUI", cat: "Render", on: true, key: 0, settings: [
            S("Show", "mode", 0, ["Bound", "Bound and enabled", "Everything"]),
            S("Align", "mode", 1, ["Left", "Right"]),
            S("Background", "toggle", true)
        ] },
        { name: "Keystrokes", cat: "Render", on: true, key: 0, settings: [
            S("Show CPS", "toggle", true)
        ] },
        { name: "Watermark", cat: "Render", on: true, key: 0, settings: [] },
        { name: "Crosshair", cat: "Render", on: true, key: 0, settings: [
            S("Gap", "slider", 3, [0, 10, 1, "px"]),
            S("Length", "slider", 5, [1, 14, 1, "px"]),
            S("React to hits", "toggle", true)
        ] },
        { name: "Fullbright", cat: "Render", on: false, key: 0, settings: [] },
        { name: "Reach Ring", cat: "Render", on: false, key: 0, settings: [
            S("Radius", "slider", 3, [1, 6, 0.05, "b"]),
            S("Color", "color", [255, 120, 60])
        ] },
        { name: "Chams", cat: "Render", on: false, key: 0, settings: [] },
        { name: "Kill Aura", cat: "Combat", on: true, key: 82, settings: [
            S("Range", "slider", 3.0, [1, 6, 0.05, "b"]),
            S("CPS", "slider", 12, [1, 20, 1, ""]),
            S("Through walls", "toggle", false)
        ], suffix: function (s) { return " " + s.setting(s.module("Kill Aura"), "Range").value.toFixed(1); } },
        { name: "Autoblock", cat: "Combat", on: true, key: 0, settings: [] },
        { name: "Velocity", cat: "Combat", on: true, key: 0, settings: [] },
        { name: "Reach", cat: "Combat", on: false, key: 0, settings: [] },
        { name: "Sprint", cat: "Movement", on: true, key: 0, settings: [] },
        { name: "Speed", cat: "Movement", on: false, key: 86, settings: [
            S("Multiplier", "slider", 1.4, [1, 3, 0.05, "x"])
        ] },
        { name: "No Slow", cat: "Movement", on: true, key: 0, settings: [] },
        { name: "Fly", cat: "Movement", on: false, key: 70, settings: [
            S("Speed", "slider", 0.5, [0.1, 2, 0.05, "x"])
        ] },
        { name: "Scaffold", cat: "Player", on: false, key: 33, settings: [] },
        { name: "Inventory Manager", cat: "Player", on: true, key: 0, settings: [] },
        { name: "Fast Place", cat: "Player", on: true, key: 0, settings: [] },
        { name: "Resource Deposit", cat: "Bedwars", on: true, key: 0, settings: [
            S("Deposit on open", "toggle", true)
        ] },
        { name: "Bed ESP", cat: "Bedwars", on: true, key: 0, settings: [] },
        { name: "Auto GG", cat: "Bedwars", on: false, key: 0, settings: [] }
    ];

    var categories = ["Combat", "Render", "Movement", "Player", "Bedwars", "Client"];

    sandbox.modules = modules;
    sandbox.categories = categories;
    sandbox.user = "anthony";

    sandbox.module = function (name) {
        for (var i = 0; i < modules.length; i++) if (modules[i].name === name) return modules[i];
        return null;
    };

    sandbox.setting = function (mod, label) {
        if (!mod) return { value: 0 };
        for (var i = 0; i < mod.settings.length; i++) {
            if (mod.settings[i].label === label) return mod.settings[i];
        }
        return { value: 0 };
    };

    sandbox.modulesIn = function (cat, search) {
        var out = [];
        var q = (search || "").toLowerCase();
        for (var i = 0; i < modules.length; i++) {
            var m = modules[i];
            if (q) {
                if (m.name.toLowerCase().indexOf(q) === -1) continue;
            } else if (m.cat !== cat) {
                continue;
            }
            out.push(m);
        }
        return out;
    };

    sandbox.onToggle = function (mod) {
        chat((mod.on ? "§aEnabled " : "§cDisabled ") + mod.name);
    };

    var KEY_NAMES = { 82: "R", 86: "V", 70: "F", 33: "PRIOR", 54: "RSHIFT", 34: "NEXT", 71: "G", 88: "X" };
    sandbox.keyName = function (code) {
        return KEY_NAMES[code] || String.fromCharCode(code) || String(code);
    };

    /* ----------------------------------------------------------------- player */

    var p = {
        x: 48, y: 15, z: 52,
        vy: 0, yaw: -Math.PI / 2, pitch: 0,
        onGround: true, sneaking: false, sprinting: false,
        limbSwing: 0, limbAmount: 0, bodyYaw: -Math.PI / 2
    };

    var perspective = 0;
    var showDebug = false;
    var swing = 0;
    var swingUntil = 0;
    var hurtUntil = 0;
    var slot = 0;
    var itemNameUntil = 0;
    var health = 20, hunger = 18, armor = 12, xp = 0.42, level = 27;
    var chatLines = [];
    var clickTimes = [];

    var hotbar = [
        { name: "Diamond Sword", color: "#66d8dd", count: 1, block: 0 },
        { name: "Oak Planks", color: "#a0793f", count: 64, block: 5 },
        { name: "Cobblestone", color: "#8b8b8b", count: 64, block: 4 },
        { name: "White Wool", color: "#e2e5e7", count: 32, block: 9 },
        { name: "Red Wool", color: "#a53434", count: 32, block: 10 },
        { name: "Blue Wool", color: "#3854a8", count: 32, block: 11 },
        { name: "Glass", color: "#c6dfe8", count: 16, block: 13 },
        { name: "Bricks", color: "#965442", count: 48, block: 8 },
        { name: "Sand", color: "#dacea0", count: 64, block: 7 }
    ];

    function chat(text) {
        chatLines.push({ text: text.replace(/§./g, ""), at: now });
        if (chatLines.length > 60) chatLines.shift();
    }

    /* ---------------------------------------------------------------- effects */

    var effects = [];
    var effectMesh = MS.GL.createDynamicMesh();

    function pushEffect(e) {
        while (effects.length >= 32) effects.shift();
        effects.push(e);
    }

    function easeOut(t) { var i = 1 - t; return 1 - i * i * i; }

    function colorOf(mod, label) {
        var c = sandbox.setting(mod, label).value;
        return [c[0] / 255, c[1] / 255, c[2] / 255];
    }

    function hitEffect(x, y, z) {
        var m = sandbox.module("Hit Effect");
        if (!m.on) return;
        var mode = sandbox.setting(m, "Mode").value;
        var dur = sandbox.setting(m, "Duration").value * 1000;
        var rgb = colorOf(m, "Color");
        if (mode !== 1) {
            pushEffect({ kind: "ripple", x: x, y: y + 0.02, z: z, born: now, life: dur,
                radius: sandbox.setting(m, "Radius").value,
                thick: sandbox.setting(m, "Thickness").value,
                rings: sandbox.setting(m, "Ripples").value, rgb: rgb });
        }
        if (mode !== 0) {
            pushEffect({ kind: "shock", x: x, y: y + 1.0, z: z, born: now, life: dur * 0.5,
                radius: sandbox.setting(m, "Radius").value * 0.7,
                thick: sandbox.setting(m, "Thickness").value, rgb: rgb });
        }
        if (sandbox.setting(m, "Sparks").value) {
            pushEffect(burst(x, y + 1.0, z, dur, sandbox.setting(m, "Spark count").value, 0.09, 0.10, rgb));
        }
    }

    function jumpEffect(x, y, z) {
        var m = sandbox.module("Jump Effect");
        if (!m.on) return;
        var mode = sandbox.setting(m, "Mode").value;
        var dur = sandbox.setting(m, "Duration").value * 1000;
        var rgb = colorOf(m, "Color");
        if (mode !== 1) {
            pushEffect({ kind: "ripple", x: x, y: y + 0.02, z: z, born: now, life: dur,
                radius: sandbox.setting(m, "Radius").value,
                thick: sandbox.setting(m, "Thickness").value, rings: 1, rgb: rgb });
        }
        if (mode !== 0) {
            pushEffect(burst(x, y, z, dur, sandbox.setting(m, "Dust count").value, 0.06, 0.05, rgb));
        }
    }

    function burst(x, y, z, life, count, speed, spread, rgb) {
        var parts = [];
        for (var i = 0; i < count; i++) {
            var a = Math.random() * Math.PI * 2;
            var h = (0.45 + Math.random() * 0.55) * speed;
            parts.push({ vx: Math.cos(a) * h, vz: Math.sin(a) * h, vy: Math.random() * spread });
        }
        return { kind: "burst", x: x, y: y, z: z, born: now, life: life, parts: parts, rgb: rgb };
    }

    var effectData = [];

    function ringBand(out, cx, cy, cz, inner, outer, rgb, alpha, vertical) {
        var mid = (inner + outer) * 0.5;
        var segments = Math.max(14, Math.min(56, Math.round(outer * 16)));
        for (var band = 0; band < 2; band++) {
            var r0 = band === 0 ? inner : mid, r1 = band === 0 ? mid : outer;
            var a0 = band === 0 ? 0 : alpha, a1 = band === 0 ? alpha : 0;
            for (var i = 0; i < segments; i++) {
                var t0 = (i / segments) * Math.PI * 2, t1 = ((i + 1) / segments) * Math.PI * 2;
                var q = [
                    point(cx, cy, cz, t0, r0, vertical), point(cx, cy, cz, t1, r0, vertical),
                    point(cx, cy, cz, t1, r1, vertical), point(cx, cy, cz, t0, r1, vertical)
                ];
                var av = [a0, a0, a1, a1];
                var order = [0, 1, 2, 0, 2, 3];
                for (var k = 0; k < 6; k++) {
                    var idx = order[k];
                    out.push(q[idx][0], q[idx][1], q[idx][2], rgb[0], rgb[1], rgb[2], av[idx]);
                }
            }
        }
    }

    function point(cx, cy, cz, t, r, vertical) {
        if (vertical) {
            // Turned to face the camera on the horizontal axis only, which is enough: a
            // shockwave is looked at from roughly level, and a full billboard costs a basis.
            var s = Math.sin(t) * r, c = Math.cos(t) * r;
            return [cx + Math.cos(p.yaw + Math.PI / 2) * s, cy + c, cz + Math.sin(p.yaw + Math.PI / 2) * s];
        }
        return [cx + Math.sin(t) * r, cy, cz + Math.cos(t) * r];
    }

    function buildEffectMesh() {
        effectData.length = 0;
        for (var i = 0; i < effects.length; i++) {
            var e = effects[i];
            var life = (now - e.born) / e.life;
            if (life >= 1 || life < 0) continue;

            if (e.kind === "ripple") {
                for (var r = 0; r < e.rings; r++) {
                    var offset = r * 0.16;
                    if (life <= offset) continue;
                    var local = (life - offset) / (1 - offset);
                    if (local >= 1) continue;
                    var travel = easeOut(local) * e.radius * (1 - r * 0.13);
                    var fade = (1 - local) * (1 - r * 0.22);
                    if (fade <= 0 || travel <= 0) continue;
                    var half = e.thick * 0.5 * (1 - local * 0.45);
                    ringBand(effectData, e.x, e.y, e.z, Math.max(0, travel - half), travel + half,
                        e.rgb, fade, false);
                }
            } else if (e.kind === "shock") {
                var t2 = easeOut(life) * e.radius;
                var h2 = e.thick * 0.5 * (1 - life * 0.5);
                ringBand(effectData, e.x, e.y, e.z, Math.max(0, t2 - h2), t2 + h2, e.rgb, 1 - life, true);
            } else {
                var elapsed = (now - e.born) / 50;
                var alpha = 1 - life * life;
                for (var q = 0; q < e.parts.length; q++) {
                    var part = e.parts[q];
                    var py = e.y + part.vy * elapsed - 0.0055 * elapsed * elapsed;
                    if (py < e.y) py = e.y;
                    quadAt(effectData, e.x + part.vx * elapsed, py + 0.05, e.z + part.vz * elapsed,
                        0.05, e.rgb, alpha);
                }
            }
        }

        var reach = sandbox.module("Reach Ring");
        if (reach.on) {
            var rr = sandbox.setting(reach, "Radius").value;
            var rgb = colorOf(reach, "Color");
            var pulse = 0.4 + 0.1 * Math.sin(now * 0.003);
            ringBand(effectData, dummy.x, dummy.y + 0.02, dummy.z, rr - 0.05, rr + 0.05, rgb, pulse, false);
        }

        MS.GL.uploadFlat(effectMesh, new Float32Array(effectData));
    }

    function quadAt(out, x, y, z, s, rgb, a) {
        var rx = Math.cos(p.yaw + Math.PI / 2) * s, rz = Math.sin(p.yaw + Math.PI / 2) * s;
        var corners = [
            [x - rx, y - s, z - rz], [x + rx, y - s, z + rz],
            [x + rx, y + s, z + rz], [x - rx, y + s, z - rz]
        ];
        var order = [0, 1, 2, 0, 2, 3];
        for (var i = 0; i < 6; i++) {
            var c = corners[order[i]];
            out.push(c[0], c[1], c[2], rgb[0], rgb[1], rgb[2], a);
        }
    }

    /* ------------------------------------------------------------------ dummy */

    var dummy = { x: 48, y: 14, z: 46, yaw: Math.PI / 2, hurt: 0 };

    /* ------------------------------------------------------------------ input */

    var keys = Object.create(null);
    var locked = false;

    function codeOf(e) {
        if (e.code === "Space") return "space";
        if (e.code.indexOf("Key") === 0) return "key" + e.code.slice(3).toLowerCase();
        if (e.code === "ShiftLeft") return "sneak";
        if (e.code === "ControlLeft") return "sprint";
        return e.code.toLowerCase();
    }

    window.addEventListener("keydown", function (e) {
        if (MS.GUI.key(e)) { e.preventDefault(); return; }

        if (e.code === "ShiftRight") {
            MS.GUI.state.open = !MS.GUI.state.open;
            if (MS.GUI.state.open) document.exitPointerLock();
            curtain.hidden = MS.GUI.state.open || locked;
            e.preventDefault();
            return;
        }
        if (e.code === "Escape") {
            if (MS.GUI.state.open) { MS.GUI.state.open = false; e.preventDefault(); }
            return;
        }
        if (e.code === "F5") { perspective = (perspective + 1) % 3; e.preventDefault(); return; }
        if (e.code === "F3") { showDebug = !showDebug; e.preventDefault(); return; }
        if (e.code === "F1") {
            var hudMod = sandbox.module("HUD");
            hudMod.on = !hudMod.on;
            e.preventDefault();
            return;
        }
        if (e.code.indexOf("Digit") === 0) {
            var n = parseInt(e.code.slice(5), 10);
            if (n >= 1 && n <= 9) { slot = n - 1; itemNameUntil = now + 1600; }
        }

        // Module binds, the way the client reads them: any key, any time the GUI is closed.
        for (var i = 0; i < modules.length; i++) {
            if (modules[i].key && modules[i].key === e.keyCode) {
                modules[i].on = !modules[i].on;
                sandbox.onToggle(modules[i]);
            }
        }

        keys[codeOf(e)] = true;
        if (e.code === "Space" || e.code.indexOf("Key") === 0 || e.code === "Tab") e.preventDefault();
    });

    window.addEventListener("keyup", function (e) { keys[codeOf(e)] = false; });

    stage.addEventListener("mousedown", function (e) {
        if (MS.GUI.state.open) return;
        if (!locked) { glCanvas.requestPointerLock(); return; }
        if (e.button === 0) attack();
        else if (e.button === 2) place();
    });

    window.addEventListener("mousedown", function (e) {
        if (MS.GUI.state.open) {
            MS.GUI.mouseDown(e.button);
            e.preventDefault();
        }
    });

    window.addEventListener("mouseup", function () { MS.GUI.mouseUp(); });
    window.addEventListener("contextmenu", function (e) { e.preventDefault(); });

    window.addEventListener("wheel", function (e) {
        if (MS.GUI.state.open) { MS.GUI.wheel(e.deltaY * 0.5); e.preventDefault(); return; }
        slot = (slot + (e.deltaY > 0 ? 1 : 8)) % 9;
        itemNameUntil = now + 1600;
    }, { passive: false });

    document.addEventListener("pointerlockchange", function () {
        locked = document.pointerLockElement === glCanvas;
        curtain.hidden = locked || MS.GUI.state.open;
    });

    document.addEventListener("mousemove", function (e) {
        if (MS.GUI.state.open) {
            var r = hudCanvas.getBoundingClientRect();
            MS.GUI.mouseMove((e.clientX - r.left) * (W / r.width), (e.clientY - r.top) * (H / r.height));
            return;
        }
        if (!locked) return;
        var sens = 0.0022;
        p.yaw += e.movementX * sens;
        p.pitch -= e.movementY * sens;
        var lim = Math.PI / 2 - 0.01;
        if (p.pitch > lim) p.pitch = lim;
        if (p.pitch < -lim) p.pitch = -lim;
    });

    /* ---------------------------------------------------------------- actions */

    function lookVector() {
        return [
            Math.cos(p.pitch) * Math.cos(p.yaw),
            Math.sin(p.pitch),
            Math.cos(p.pitch) * Math.sin(p.yaw)
        ];
    }

    function eyeY() {
        return p.y + (p.sneaking ? 1.54 : 1.62);
    }

    function attack() {
        swing = 1;
        swingUntil = now + 220;
        clickTimes.push(now);

        // The dummy is hit first if it is inside reach and roughly in front, which is what makes
        // the hit effect fire where a player would expect it to.
        var reach = sandbox.setting(sandbox.module("Kill Aura"), "Range").value;
        var dx = dummy.x - p.x, dz = dummy.z - p.z;
        var dist = Math.hypot(dx, dz);
        var toward = Math.atan2(dz, dx);
        var delta = Math.abs(((toward - p.yaw + Math.PI * 3) % (Math.PI * 2)) - Math.PI);
        if (dist <= reach + 0.6 && delta < 0.9) {
            dummy.hurt = now + 300;
            hitEffect(dummy.x, dummy.y, dummy.z);
            chat("§7hit dummy for 6.5");
            return;
        }

        var v = lookVector();
        var hit = MS.World.raycast(p.x, eyeY(), p.z, v[0], v[1], v[2], 5);
        if (hit) {
            MS.World.set(hit.x, hit.y, hit.z, 0);
            hitEffect(hit.x + 0.5, hit.y, hit.z + 0.5);
        }
    }

    function place() {
        var v = lookVector();
        var hit = MS.World.raycast(p.x, eyeY(), p.z, v[0], v[1], v[2], 5);
        if (!hit) return;
        var block = hotbar[slot].block;
        if (!block) return;
        var nx = hit.x + hit.nx, ny = hit.y + hit.ny, nz = hit.z + hit.nz;
        // Refuse a block that would be placed inside the player, the way the game does.
        var half = MS.World.PLAYER_HALF;
        if (nx === Math.floor(p.x) && nz === Math.floor(p.z)
            && ny >= Math.floor(p.y) && ny <= Math.floor(p.y + 1.7)) {
            return;
        }
        if (MS.World.set(nx, ny, nz, block)) {
            swing = 1;
        }
        void half;
    }

    /* ----------------------------------------------------------------- update */

    function update(dt) {
        var m;
        p.sneaking = !!keys.sneak && p.onGround;
        var forward = (keys.keyw ? 1 : 0) - (keys.keys ? 1 : 0);
        var strafe = (keys.keyd ? 1 : 0) - (keys.keya ? 1 : 0);
        p.sprinting = (!!keys.sprint || false) && forward > 0 && !p.sneaking;

        var speed = 4.3;
        if (p.sprinting) speed = 5.6;
        if (p.sneaking) speed = 1.3;
        m = sandbox.module("Speed");
        if (m.on) speed *= sandbox.setting(m, "Multiplier").value;

        var dx = 0, dz = 0;
        if (forward || strafe) {
            var len = Math.hypot(forward, strafe);
            dx = (Math.cos(p.yaw) * forward - Math.sin(p.yaw) * strafe) / len * speed * dt;
            dz = (Math.sin(p.yaw) * forward + Math.cos(p.yaw) * strafe) / len * speed * dt;
            p.bodyYaw = Math.atan2(dz, dx);
        }

        var fly = sandbox.module("Fly");
        if (fly.on) {
            var fs = sandbox.setting(fly, "Speed").value * 9;
            p.vy = 0;
            if (keys.space) p.y += fs * dt;
            if (keys.sneak) p.y -= fs * dt;
            MS.World.move(p, dx, 0, dz, false);
            p.onGround = false;
        } else {
            if (keys.space && p.onGround) {
                p.vy = 8.6;
                p.onGround = false;
                jumpEffect(p.x, p.y, p.z);
            }
            p.vy -= 27 * dt;
            if (p.vy < -40) p.vy = -40;
            p.onGround = MS.World.move(p, dx, p.vy * dt, dz, true);
            if (p.onGround && p.vy < 0) p.vy = 0;
        }

        var moved = Math.hypot(dx, dz);
        p.limbSwing += moved * 14;
        p.limbAmount += (Math.min(1, moved * 22) - p.limbAmount) * 0.28;

        if (swing > 0) {
            swing -= dt * 4.6;
            if (swing < 0) swing = 0;
        }

        for (var i = effects.length - 1; i >= 0; i--) {
            if (now - effects[i].born > effects[i].life) effects.splice(i, 1);
        }
        while (clickTimes.length && now - clickTimes[0] > 1000) clickTimes.shift();

        // A fall that would hurt flashes the screen, which is the cheapest way the world feels
        // like it is being interacted with rather than flown over.
        if (p.onGround && p.vy === 0 && fallStart - p.y > 4) {
            hurtUntil = now + 450;
            health = Math.max(2, health - 2);
            chat("§cyou hit the ground too hard");
        }
        if (p.onGround) fallStart = p.y;
        else fallStart = Math.max(fallStart, p.y);

        dummy.yaw = Math.atan2(p.z - dummy.z, p.x - dummy.x);
    }

    var fallStart = 15;

    /* ------------------------------------------------------------------ frame */

    var worldMesh = MS.GL.createMesh();
    var selectionMesh = null;
    var now = performance.now();
    var last = now;
    var frames = 0, fpsAt = now, fps = 0;

    function buildSelectionMesh() {
        var e = [
            [0, 0, 0, 1, 0, 0], [1, 0, 0, 1, 1, 0], [1, 1, 0, 0, 1, 0], [0, 1, 0, 0, 0, 0],
            [0, 0, 1, 1, 0, 1], [1, 0, 1, 1, 1, 1], [1, 1, 1, 0, 1, 1], [0, 1, 1, 0, 0, 1],
            [0, 0, 0, 0, 0, 1], [1, 0, 0, 1, 0, 1], [1, 1, 0, 1, 1, 1], [0, 1, 0, 0, 1, 1]
        ];
        var data = [];
        for (var i = 0; i < e.length; i++) {
            data.push(e[i][0], e[i][1], e[i][2], e[i][3], e[i][4], e[i][5]);
        }
        selectionMesh = MS.GL.createLineMesh(new Float32Array(data));
    }

    function facingName() {
        var d = ((p.yaw * 180 / Math.PI) % 360 + 360) % 360;
        if (d < 45 || d >= 315) return "east";
        if (d < 135) return "south";
        if (d < 225) return "west";
        return "north";
    }

    function frame(ts) {
        now = ts;
        var dt = Math.min(0.05, (ts - last) / 1000);
        last = ts;

        if (!MS.GUI.state.open) update(dt);

        if (MS.World.dirty) MS.World.buildMesh(worldMesh);

        var mat = MS.GL.mat;
        var aspect = W / H;
        var fovBoost = p.sprinting ? 0.06 : 0;
        var proj = mat.perspective(Math.PI / 3 + fovBoost, aspect, 0.05, 260);

        // Third person pulls the camera back along the view ray and stops at whatever it meets,
        // so the view never ends up inside a wall.
        var camX = p.x, camY = eyeY(), camZ = p.z;
        var camYaw = p.yaw, camPitch = p.pitch;
        if (perspective !== 0) {
            var back = perspective === 1 ? 1 : -1;
            if (perspective === 2) { camYaw = p.yaw + Math.PI; camPitch = -p.pitch; }
            var dir = [
                -Math.cos(p.pitch) * Math.cos(p.yaw) * back,
                -Math.sin(p.pitch) * back,
                -Math.cos(p.pitch) * Math.sin(p.yaw) * back
            ];
            var want = 4.2;
            var hitBack = MS.World.raycast(p.x, camY, p.z, dir[0], dir[1], dir[2], want);
            var dist = hitBack ? Math.max(0.6, hitBack.dist - 0.25) : want;
            camX = p.x + dir[0] * dist;
            camY = camY + dir[1] * dist;
            camZ = p.z + dir[2] * dist;
        }

        var bright = sandbox.module("Fullbright").on ? 0.75 : 0;
        var view = mat.view(camX, camY, camZ, camYaw, camPitch);
        var fog = [0.62, 0.74, 0.92];

        MS.GL.beginFrame(glCanvas.width, glCanvas.height, proj, view, fog, bright);
        MS.GL.drawMesh(worldMesh, null, null);

        // The dummy, and the player themselves when the camera is not in their head.
        var hurtTint = dummy.hurt > now ? new Float32Array([1, 0.25, 0.25, 0.55]) : null;
        MS.Entity.drawHumanoid(dummy.x, dummy.y, dummy.z, dummy.yaw, dummy.yaw, 0, 0, 0, 0, hurtTint, false);
        if (perspective !== 0) {
            MS.Entity.drawHumanoid(p.x, p.y, p.z, p.bodyYaw, p.yaw, p.pitch,
                p.limbSwing, p.limbAmount, swing, null, true);
        }

        if (!selectionMesh) buildSelectionMesh();
        var v = lookVector();
        var look = MS.World.raycast(p.x, eyeY(), p.z, v[0], v[1], v[2], 5);
        var lookingAt = null;
        if (look) {
            lookingAt = MS.World.TYPES[MS.World.get(look.x, look.y, look.z)].name
                + " (" + look.x + " " + look.y + " " + look.z + ")";
            MS.GL.drawLines(selectionMesh,
                mat.multiply(new Float32Array(16),
                    mat.translation(look.x - 0.002, look.y - 0.002, look.z - 0.002),
                    mat.scaling(1.004, 1.004, 1.004)),
                new Float32Array([0, 0, 0, 0.45]), 2);
        }

        buildEffectMesh();
        MS.GL.drawFlat(effectMesh);

        if (perspective === 0) {
            // The hand is parented to the camera, so it gets its own near projection and an
            // identity view; drawn last so the world can never poke through it.
            var handProj = mat.perspective(Math.PI / 3, aspect, 0.01, 6);
            MS.GL.setCamera(handProj, mat.identity(), fog, bright);
            MS.GL.ctx.clear(MS.GL.ctx.DEPTH_BUFFER_BIT);
            MS.Entity.drawFirstPersonItem(swing, p.sneaking,
                Math.sin(p.limbSwing * 0.5) * p.limbAmount,
                Math.abs(Math.cos(p.limbSwing * 0.5)) * p.limbAmount);
        }

        /* ---- overlay ---- */
        hud.setTransform(DPR, 0, 0, DPR, 0, 0);
        hud.clearRect(0, 0, W, H);
        hud.textAlign = "left";
        hud.textBaseline = "top";

        var enabled = 0;
        for (var i2 = 0; i2 < modules.length; i2++) if (modules[i2].on && !modules[i2].hidden) enabled++;

        var state = {
            now: now, fps: fps, p: p, modules: modules, keys: keys,
            module: sandbox.module, setting: sandbox.setting, keyName: sandbox.keyName,
            user: sandbox.user, perspective: perspective, swingUntil: swingUntil,
            hurtUntil: hurtUntil, chat: chatLines, chatOpen: false,
            health: health, hunger: hunger, armor: armor, xp: xp, level: level,
            hotbar: hotbar, slot: slot, itemNameUntil: itemNameUntil,
            cps: clickTimes.length, effectCount: effects.length, enabledCount: enabled,
            triangles: MS.World.triangles || 0, facing: facingName(),
            yawDeg: (p.yaw * 180 / Math.PI) % 360, pitchDeg: p.pitch * 180 / Math.PI,
            looking: lookingAt
        };
        state.keystrokeTop = MS.HUD.keystrokeTop(state);

        MS.HUD.drawOverlayTint(state);
        MS.HUD.drawVanilla(state);
        MS.HUD.drawCrosshair(state);
        MS.HUD.drawWatermark(state);
        MS.HUD.drawArrayList(state);
        MS.HUD.drawBindList(state);
        MS.HUD.drawKeystrokes(state);
        MS.HUD.drawChat(state);
        if (showDebug) MS.HUD.drawDebug(state);
        MS.GUI.draw(sandbox);

        sandbox.now = now;

        frames++;
        if (ts - fpsAt > 500) {
            fps = Math.round(frames * 1000 / (ts - fpsAt));
            frames = 0;
            fpsAt = ts;
        }

        requestAnimationFrame(frame);
    }

    MS.World.generate();
    MS.Entity.init();
    resize();
    chat("§dmindless §7sandbox ready");
    chat("§7f5 perspective, f3 debug, f1 hud, right shift gui");
    requestAnimationFrame(frame);
}(window.MS = window.MS || {}));
