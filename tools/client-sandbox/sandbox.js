/*
 * Mindless client sandbox.
 *
 * A standing-in-a-world preview of the parts of the client that are tuned by eye: the array list
 * and its connected background, the click GUI, the bind list, and the effect presets. Everything
 * here that has a counterpart in Java is a transcription of that counterpart, not an impression of
 * it, so a radius or an easing curve settled here is settled for the client too.
 *
 * The world itself is a grid raycaster. It is not trying to be Minecraft; it exists so the HUD is
 * seen over moving terrain at a believable field of view instead of over a flat colour, which is
 * the only way to judge a translucent panel honestly.
 */
(function () {
    "use strict";

    var view = document.getElementById("view");
    var ctx = view.getContext("2d", { alpha: false });
    var game = document.getElementById("game");
    var curtain = document.getElementById("curtain");

    var W = 0, H = 0, DPR = 1;

    function resize() {
        DPR = Math.min(2, window.devicePixelRatio || 1);
        var r = game.getBoundingClientRect();
        W = Math.max(320, Math.floor(r.width));
        H = Math.max(240, Math.floor(r.height));
        view.width = Math.floor(W * DPR);
        view.height = Math.floor(H * DPR);
        ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
        ctx.imageSmoothingEnabled = false;
    }

    window.addEventListener("resize", resize);

    /* ------------------------------------------------------------------ world */

    var MAP_W = 40, MAP_H = 40;
    var map = new Uint8Array(MAP_W * MAP_H);

    var BLOCKS = [
        null,
        { top: "#6f9b41", side: "#7a5a3a", name: "grass" },
        { top: "#8d8d8d", side: "#7b7b7b", name: "stone" },
        { top: "#c8b072", side: "#b79d61", name: "sand" },
        { top: "#b23c3c", side: "#9c3333", name: "wool" },
        { top: "#3f6fb5", side: "#36609e", name: "lapis" },
        { top: "#d8d8d8", side: "#c4c4c4", name: "quartz" }
    ];

    function at(x, z) {
        if (x < 0 || z < 0 || x >= MAP_W || z >= MAP_H) return 2;
        return map[z * MAP_W + x];
    }

    function buildWorld() {
        var x, z;
        for (z = 0; z < MAP_H; z++) {
            for (x = 0; x < MAP_W; x++) {
                var border = x === 0 || z === 0 || x === MAP_W - 1 || z === MAP_H - 1;
                map[z * MAP_W + x] = border ? 2 : 0;
            }
        }
        // A handful of structures so there is something to walk around and something for the
        // array list to sit in front of at different brightnesses.
        function box(x0, z0, w, d, block) {
            for (var zz = z0; zz < z0 + d; zz++) {
                for (var xx = x0; xx < x0 + w; xx++) {
                    if (zz === z0 || zz === z0 + d - 1 || xx === x0 || xx === x0 + w - 1) {
                        map[zz * MAP_W + xx] = block;
                    }
                }
            }
        }
        box(6, 6, 7, 7, 4);
        box(24, 8, 6, 9, 5);
        box(10, 24, 11, 8, 6);
        box(28, 26, 5, 5, 3);
        map[7 * MAP_W + 9] = 0;
        map[12 * MAP_W + 9] = 0;
        map[26 * MAP_W + 15] = 0;
    }

    /* ----------------------------------------------------------------- camera */

    var cam = {
        x: 20.5, z: 34.0,
        yaw: -Math.PI / 2, pitch: 0,
        eye: 1.62, vy: 0, y: 0,
        onGround: true, sneaking: false, sprinting: false,
        bob: 0
    };

    var FOV = Math.PI / 3;

    var keys = Object.create(null);
    var locked = false;

    /* ---------------------------------------------------------------- modules */

    function S(label, type, value, extra) {
        var s = { label: label, type: type, value: value, visible: true };
        if (type === "slider") { s.min = extra[0]; s.max = extra[1]; s.step = extra[2]; s.unit = extra[3] || ""; }
        if (type === "mode") { s.options = extra; }
        return s;
    }

    var modules = [
        { name: "HUD", cat: "Render", on: true, key: 0, settings: [
            S("Draw background", "toggle", true),
            S("Background mode", "mode", 0, ["Connected", "Per line", "Panel"]),
            S("Rounded background", "toggle", true),
            S("Corner radius", "slider", 4, [0, 20, 0.5, ""]),
            S("Step rounding", "slider", 100, [0, 100, 5, "%"]),
            S("Row separators", "toggle", true),
            S("Background opacity", "slider", 43, [0, 100, 1, "%"]),
            S("Align right", "toggle", true),
            S("Lowercase", "toggle", false)
        ] },
        { name: "Hit Effect", cat: "Render", on: true, key: 0, settings: [
            S("Mode", "mode", 0, ["Ripple", "Shockwave", "Both"]),
            S("Duration", "slider", 0.9, [0.2, 3, 0.05, "s"]),
            S("Radius", "slider", 3, [0.5, 8, 0.1, "b"]),
            S("Thickness", "slider", 0.18, [0.02, 1, 0.02, "b"]),
            S("Ripples", "slider", 3, [1, 6, 1, ""]),
            S("Sparks", "toggle", false),
            S("Spark count", "slider", 18, [4, 80, 1, ""])
        ] },
        { name: "Jump Effect", cat: "Render", on: true, key: 0, settings: [
            S("Mode", "mode", 0, ["Ring", "Dust", "Both"]),
            S("Duration", "slider", 0.7, [0.2, 2, 0.05, "s"]),
            S("Radius", "slider", 1.8, [0.4, 5, 0.1, "b"]),
            S("Thickness", "slider", 0.14, [0.02, 0.8, 0.02, "b"]),
            S("Dust count", "slider", 18, [4, 60, 1, ""])
        ] },
        { name: "Bind GUI", cat: "Render", on: true, key: 0, settings: [
            S("Show", "mode", 0, ["Bound", "Bound and enabled", "Everything"]),
            S("Align", "mode", 0, ["Left", "Right"]),
            S("Background", "toggle", true)
        ] },
        { name: "Keystrokes", cat: "Render", on: true, key: 0, settings: [] },
        { name: "Watermark", cat: "Render", on: true, key: 0, settings: [] },
        { name: "Crosshair", cat: "Render", on: true, key: 0, settings: [
            S("Gap", "slider", 3, [0, 10, 1, "px"]),
            S("Length", "slider", 5, [1, 14, 1, "px"]),
            S("React to hits", "toggle", true)
        ] },
        { name: "Fullbright", cat: "Render", on: true, key: 0, settings: [] },
        { name: "Chams", cat: "Render", on: false, key: 0, settings: [] },
        { name: "Kill Aura", cat: "Combat", on: true, key: 82, settings: [
            S("Range", "slider", 3.0, [1, 6, 0.05, "b"]),
            S("CPS", "slider", 12, [1, 20, 1, ""]),
            S("Through walls", "toggle", false)
        ] },
        { name: "Autoblock", cat: "Combat", on: true, key: 0, settings: [] },
        { name: "Reach", cat: "Combat", on: false, key: 0, settings: [] },
        { name: "Velocity", cat: "Combat", on: true, key: 0, settings: [] },
        { name: "Sprint", cat: "Movement", on: true, key: 0, settings: [] },
        { name: "Speed", cat: "Movement", on: false, key: 86, settings: [] },
        { name: "No Slow", cat: "Movement", on: true, key: 0, settings: [] },
        { name: "Scaffold", cat: "Player", on: false, key: 33, settings: [] },
        { name: "Inventory Manager", cat: "Player", on: true, key: 0, settings: [] },
        { name: "Fast Place", cat: "Player", on: true, key: 0, settings: [] },
        { name: "Resource Deposit", cat: "Bedwars", on: true, key: 0, settings: [] },
        { name: "Bed ESP", cat: "Bedwars", on: true, key: 0, settings: [] },
        { name: "Auto GG", cat: "Bedwars", on: false, key: 0, settings: [] }
    ];

    var CATEGORIES = ["Combat", "Render", "Movement", "Player", "Bedwars"];

    function setting(mod, label) {
        for (var i = 0; i < mod.settings.length; i++) {
            if (mod.settings[i].label === label) return mod.settings[i];
        }
        return null;
    }

    function moduleByName(name) {
        for (var i = 0; i < modules.length; i++) {
            if (modules[i].name === name) return modules[i];
        }
        return null;
    }

    function val(name, label, fallback) {
        var m = moduleByName(name);
        if (!m) return fallback;
        var s = setting(m, label);
        return s ? s.value : fallback;
    }

    var hud = moduleByName("HUD");

    /* ---------------------------------------------------------------- effects */

    var effects = [];
    var TICK_MS = 50;

    function spawnEffect(e) {
        while (effects.length >= 32) effects.shift();
        effects.push(e);
    }

    function easeOut(t) { var i = 1 - t; return 1 - i * i * i; }

    function hitEffect(wx, wy, wz) {
        var m = moduleByName("Hit Effect");
        if (!m || !m.on) return;
        var mode = setting(m, "Mode").value;
        var dur = setting(m, "Duration").value * 1000;
        if (mode !== 1) {
            spawnEffect({ kind: "ripple", x: wx, y: wy, z: wz, born: now, life: dur,
                radius: setting(m, "Radius").value,
                thick: setting(m, "Thickness").value,
                rings: setting(m, "Ripples").value, rgb: [79, 216, 255] });
        }
        if (mode !== 0) {
            spawnEffect({ kind: "shock", x: wx, y: wy + 1.0, z: wz, born: now, life: dur * 0.5,
                radius: setting(m, "Radius").value * 0.7,
                thick: setting(m, "Thickness").value, rgb: [79, 216, 255] });
        }
        if (setting(m, "Sparks").value) {
            spawnEffect(burst(wx, wy + 1.0, wz, dur, setting(m, "Spark count").value,
                0.09, 0.10, [79, 216, 255]));
        }
    }

    function jumpEffect(wx, wy, wz) {
        var m = moduleByName("Jump Effect");
        if (!m || !m.on) return;
        var mode = setting(m, "Mode").value;
        var dur = setting(m, "Duration").value * 1000;
        if (mode !== 1) {
            spawnEffect({ kind: "ripple", x: wx, y: wy, z: wz, born: now, life: dur,
                radius: setting(m, "Radius").value,
                thick: setting(m, "Thickness").value,
                rings: 1, rgb: [255, 79, 163] });
        }
        if (mode !== 0) {
            spawnEffect(burst(wx, wy, wz, dur, setting(m, "Dust count").value,
                0.06, 0.05, [255, 79, 163]));
        }
    }

    function burst(wx, wy, wz, life, count, speed, spread, rgb) {
        var parts = [];
        for (var i = 0; i < count; i++) {
            var a = Math.random() * Math.PI * 2;
            var h = (0.45 + Math.random() * 0.55) * speed;
            parts.push({ vx: Math.cos(a) * h, vz: Math.sin(a) * h, vy: Math.random() * spread });
        }
        return { kind: "burst", x: wx, y: wy, z: wz, born: now, life: life, parts: parts, rgb: rgb };
    }

    /* ------------------------------------------------------------- projection */

    // Camera-relative projection. Returns null behind the near plane so a ring that straddles the
    // camera does not fold back across the screen.
    var focal = 1;

    function project(wx, wy, wz) {
        var dx = wx - cam.x, dz = wz - cam.z;
        var s = Math.sin(cam.yaw), c = Math.cos(cam.yaw);
        var rx = dx * c + dz * s;
        var rz = -dx * s + dz * c;
        if (rz < 0.08) return null;
        var eyeY = cam.eye + cam.y - (cam.sneaking ? 0.22 : 0);
        return {
            x: W / 2 + (rx / rz) * focal,
            y: H / 2 + horizonShift() + ((eyeY - wy) / rz) * focal,
            d: rz,
            k: focal / rz
        };
    }

    function horizonShift() { return Math.tan(cam.pitch) * focal + cam.bob; }

    /* ---------------------------------------------------------------- raycast */

    function renderWorld() {
        focal = (W / 2) / Math.tan(FOV / 2);
        var horizon = H / 2 + horizonShift();

        var sky = ctx.createLinearGradient(0, 0, 0, Math.max(1, horizon));
        sky.addColorStop(0, "#5a86c8");
        sky.addColorStop(1, "#a8c4e4");
        ctx.fillStyle = sky;
        ctx.fillRect(0, 0, W, Math.max(0, horizon));

        var ground = ctx.createLinearGradient(0, horizon, 0, H);
        ground.addColorStop(0, "#4a6a33");
        ground.addColorStop(1, "#2f4322");
        ctx.fillStyle = ground;
        ctx.fillRect(0, Math.max(0, horizon), W, H - Math.max(0, horizon));

        var columns = Math.max(160, Math.floor(W / 2));
        var colW = W / columns;

        for (var i = 0; i < columns; i++) {
            var camX = (2 * i / columns) - 1;
            var screenX = camX * Math.tan(FOV / 2);
            var rayA = cam.yaw + Math.atan(screenX);
            var rdx = Math.cos(rayA), rdz = Math.sin(rayA);

            var mx = Math.floor(cam.x), mz = Math.floor(cam.z);
            var ddx = Math.abs(1 / (rdx || 1e-9)), ddz = Math.abs(1 / (rdz || 1e-9));
            var stepX, stepZ, sideX, sideZ;

            if (rdx < 0) { stepX = -1; sideX = (cam.x - mx) * ddx; }
            else { stepX = 1; sideX = (mx + 1 - cam.x) * ddx; }
            if (rdz < 0) { stepZ = -1; sideZ = (cam.z - mz) * ddz; }
            else { stepZ = 1; sideZ = (mz + 1 - cam.z) * ddz; }

            var hit = 0, side = 0, guard = 0;
            while (!hit && guard++ < 96) {
                if (sideX < sideZ) { sideX += ddx; mx += stepX; side = 0; }
                else { sideZ += ddz; mz += stepZ; side = 1; }
                hit = at(mx, mz);
            }
            if (!hit) continue;

            var perp = side === 0 ? (sideX - ddx) : (sideZ - ddz);
            perp *= Math.cos(rayA - cam.yaw);
            if (perp < 0.02) perp = 0.02;

            var wallH = focal / perp;
            var eyeY = cam.eye + cam.y - (cam.sneaking ? 0.22 : 0);
            var top = horizon - (1 - eyeY) * (focal / perp) - (wallH - wallH);
            top = horizon - ((1 - eyeY) / perp) * focal - (1 / perp) * focal;
            var bottom = horizon + (eyeY / perp) * focal;

            var block = BLOCKS[hit];
            var base = side === 0 ? block.side : block.top;
            var shade = Math.max(0.32, 1 - perp / 26) * (side === 0 ? 0.82 : 1);
            ctx.fillStyle = tint(base, shade);
            ctx.fillRect(i * colW, top, colW + 1, bottom - top + 1);
        }
    }

    function tint(hex, f) {
        var r = parseInt(hex.slice(1, 3), 16) * f;
        var g = parseInt(hex.slice(3, 5), 16) * f;
        var b = parseInt(hex.slice(5, 7), 16) * f;
        return "rgb(" + (r | 0) + "," + (g | 0) + "," + (b | 0) + ")";
    }

    /* ---------------------------------------------------------- effect render */

    function renderEffects() {
        ctx.save();
        ctx.globalCompositeOperation = "lighter";
        for (var i = 0; i < effects.length; i++) {
            var e = effects[i];
            var life = (now - e.born) / e.life;
            if (life >= 1) continue;
            if (e.kind === "ripple") drawRipple(e, life);
            else if (e.kind === "shock") drawShock(e, life);
            else drawBurst(e, life);
        }
        ctx.restore();
    }

    function ringPath(e, radius) {
        var steps = 48, started = false;
        ctx.beginPath();
        for (var i = 0; i <= steps; i++) {
            var t = (i / steps) * Math.PI * 2;
            var p = project(e.x + Math.sin(t) * radius, e.y, e.z + Math.cos(t) * radius);
            if (!p) { started = false; continue; }
            if (!started) { ctx.moveTo(p.x, p.y); started = true; }
            else ctx.lineTo(p.x, p.y);
        }
        return started;
    }

    function drawRipple(e, life) {
        for (var r = 0; r < e.rings; r++) {
            var offset = r * 0.16;
            if (life <= offset) continue;
            var local = (life - offset) / (1 - offset);
            if (local >= 1) continue;
            var travel = easeOut(local) * e.radius * (1 - r * 0.13);
            var fade = (1 - local) * (1 - r * 0.22);
            if (fade <= 0 || travel <= 0) continue;
            var mid = project(e.x, e.y, e.z);
            if (!mid) continue;
            if (!ringPath(e, travel)) continue;
            ctx.strokeStyle = "rgba(" + e.rgb[0] + "," + e.rgb[1] + "," + e.rgb[2] + "," + fade.toFixed(3) + ")";
            ctx.lineWidth = Math.max(1, e.thick * mid.k * (1 - local * 0.45));
            ctx.stroke();
        }
    }

    function drawShock(e, life) {
        var p = project(e.x, e.y, e.z);
        if (!p) return;
        var travel = easeOut(life) * e.radius;
        var fade = 1 - life;
        ctx.beginPath();
        ctx.arc(p.x, p.y, travel * p.k, 0, Math.PI * 2);
        ctx.strokeStyle = "rgba(" + e.rgb[0] + "," + e.rgb[1] + "," + e.rgb[2] + "," + fade.toFixed(3) + ")";
        ctx.lineWidth = Math.max(1, e.thick * p.k);
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(p.x, p.y, travel * p.k * 0.82, 0, Math.PI * 2);
        ctx.strokeStyle = "rgba(255,255,255," + (fade * 0.35).toFixed(3) + ")";
        ctx.lineWidth = Math.max(1, e.thick * p.k * 0.5);
        ctx.stroke();
    }

    function drawBurst(e, life) {
        var t = (now - e.born) / TICK_MS;
        var alpha = 1 - life * life;
        for (var i = 0; i < e.parts.length; i++) {
            var q = e.parts[i];
            var py = e.y + q.vy * t - 0.055 * t * t;
            if (py < e.y) py = e.y;
            var p = project(e.x + q.vx * t, py, e.z + q.vz * t);
            if (!p) continue;
            var size = Math.max(1, 0.05 * p.k);
            ctx.fillStyle = "rgba(" + e.rgb[0] + "," + e.rgb[1] + "," + e.rgb[2] + "," + alpha.toFixed(3) + ")";
            ctx.beginPath();
            ctx.arc(p.x, p.y, size, 0, Math.PI * 2);
            ctx.fill();
        }
    }

    /* ------------------------------------------------------------- array list */

    // Transcribed from HUD.paintBackgroundShapes. A width change is one convex corner of the
    // silhouette and one reflex one; the wider row keeps a quarter disc and the narrower row gets
    // the corner square minus that disc, which is what stops the ragged edge biting notches.
    function arrayListRows(font) {
        var rows = [];
        for (var i = 0; i < modules.length; i++) {
            if (!modules[i].on) continue;
            if (modules[i].name === "HUD") continue;
            var label = modules[i].name;
            if (val("HUD", "Lowercase", false)) label = label.toLowerCase();
            rows.push({ text: label, w: Math.round(ctx.measureText(label).width) });
        }
        rows.sort(function (a, b) { return b.w - a.w; });
        return rows;
    }

    function roundedPath(x1, y1, x2, y2, tl, tr, br, bl) {
        ctx.beginPath();
        ctx.moveTo(x1 + tl, y1);
        ctx.lineTo(x2 - tr, y1);
        if (tr > 0) ctx.arcTo(x2, y1, x2, y1 + tr, tr); else ctx.lineTo(x2, y1);
        ctx.lineTo(x2, y2 - br);
        if (br > 0) ctx.arcTo(x2, y2, x2 - br, y2, br); else ctx.lineTo(x2, y2);
        ctx.lineTo(x1 + bl, y2);
        if (bl > 0) ctx.arcTo(x1, y2, x1, y2 - bl, bl); else ctx.lineTo(x1, y2);
        ctx.lineTo(x1, y1 + tl);
        if (tl > 0) ctx.arcTo(x1, y1, x1 + tl, y1, tl); else ctx.lineTo(x1, y1);
        ctx.closePath();
    }

    function inverseCorner(px, py, r, sx, sy) {
        if (r <= 0) return;
        var cx = px + sx * r, cy = py + sy * r;
        ctx.beginPath();
        ctx.moveTo(px, py);
        for (var i = 0; i <= 10; i++) {
            var t = (i / 10) * Math.PI / 2;
            ctx.lineTo(cx - sx * r * Math.cos(t), cy - sy * r * Math.sin(t));
        }
        ctx.closePath();
        ctx.fill();
    }

    function drawArrayList() {
        if (!hud || !hud.on) return;
        var pad = 3, rowH = 12;
        ctx.font = "11px system-ui, sans-serif";
        ctx.textBaseline = "top";
        var rows = arrayListRows();
        if (!rows.length) return;

        var right = val("HUD", "Align right", true);
        var anchorX = right ? W - 5 : 5;
        var top = 40;

        var drawBg = val("HUD", "Draw background", true);
        var mode = val("HUD", "Background mode", 0);
        var rounded = val("HUD", "Rounded background", true);
        var radiusSetting = rounded ? val("HUD", "Corner radius", 4) : 0;
        var stepPct = val("HUD", "Step rounding", 100) / 100;
        var alpha = val("HUD", "Background opacity", 43) / 100;

        var narrowest = Infinity;
        for (var n = 0; n < rows.length; n++) narrowest = Math.min(narrowest, rows[n].w + pad * 2);
        var radius = Math.max(0, Math.min(radiusSetting, Math.min(rowH, narrowest * 0.5)));
        var transition = Math.min(radius * stepPct, rowH * 0.5);

        if (drawBg) {
            ctx.fillStyle = "rgba(0,0,0," + alpha.toFixed(3) + ")";
            if (mode === 2) {
                var maxW = 0;
                for (var m2 = 0; m2 < rows.length; m2++) maxW = Math.max(maxW, rows[m2].w);
                var pl = right ? anchorX - maxW - pad : anchorX - pad;
                roundedPath(pl, top, pl + maxW + pad * 2, top + rows.length * rowH,
                    radius, radius, radius, radius);
                ctx.fill();
            } else if (mode === 1) {
                for (var j = 0; j < rows.length; j++) {
                    var lw = rows[j].w + pad * 2;
                    var lx = right ? anchorX - rows[j].w - pad : anchorX - pad;
                    var rr = Math.max(0, Math.min(radiusSetting, Math.min(lw, rowH) * 0.5));
                    roundedPath(lx, top + j * rowH, lx + lw, top + (j + 1) * rowH, rr, rr, rr, rr);
                    ctx.fill();
                }
            } else {
                for (var k = 0; k < rows.length; k++) {
                    var w = rows[k].w + pad * 2;
                    var x1 = right ? anchorX - rows[k].w - pad : anchorX - pad;
                    var x2 = x1 + w;
                    var y1 = top + k * rowH, y2 = y1 + rowH;
                    var first = k === 0, last = k === rows.length - 1;

                    var above = first ? 0 : rows[k].w - rows[k - 1].w;
                    var below = last ? 0 : rows[k].w - rows[k + 1].w;
                    var ragTop = first ? radius : (above > 1 ? Math.min(transition, above) : 0);
                    var ragBot = last ? radius : (below > 1 ? Math.min(transition, below) : 0);
                    var filTop = above < -1 ? Math.min(transition, -above) : 0;
                    var filBot = below < -1 ? Math.min(transition, -below) : 0;
                    var aliTop = first ? radius : 0;
                    var aliBot = last ? radius : 0;

                    roundedPath(x1, y1, x2, y2,
                        right ? ragTop : aliTop, right ? aliTop : ragTop,
                        right ? aliBot : ragBot, right ? ragBot : aliBot);
                    ctx.fill();

                    var ragX = right ? x1 : x2;
                    var side = right ? -1 : 1;
                    inverseCorner(ragX, y1, filTop, side, 1);
                    inverseCorner(ragX, y2, filBot, side, -1);
                }
            }

            if (val("HUD", "Row separators", true) && mode === 0 && rows.length > 1) {
                ctx.fillStyle = "rgba(255,255,255,0.10)";
                for (var s2 = 0; s2 + 1 < rows.length; s2++) {
                    var shared = Math.min(rows[s2].w, rows[s2 + 1].w) + pad * 2;
                    var sx = right ? anchorX + pad - shared : anchorX - pad;
                    ctx.fillRect(sx, top + (s2 + 1) * rowH - 0.5, shared, 1);
                }
            }
        }

        for (var t2 = 0; t2 < rows.length; t2++) {
            var hue = (now / 24 + t2 * 18) % 360;
            ctx.fillStyle = "hsl(" + hue + ", 82%, 68%)";
            var tx = right ? anchorX - rows[t2].w : anchorX;
            ctx.fillText(rows[t2].text, tx, top + t2 * rowH + 1);
        }
    }

    /* ------------------------------------------------------------------- hud */

    function drawWatermark() {
        if (!moduleByName("Watermark").on) return;
        ctx.font = "600 15px system-ui, sans-serif";
        ctx.textBaseline = "top";
        ctx.fillStyle = "rgba(0,0,0,0.45)";
        ctx.fillText("mindless", 6, 7);
        ctx.fillStyle = "#ff4fa3";
        ctx.fillText("mindless", 5, 6);
    }

    function drawBindList() {
        var m = moduleByName("Bind GUI");
        if (!m || !m.on) return;
        var show = setting(m, "Show").value;
        var rows = [];
        for (var i = 0; i < modules.length; i++) {
            var mod = modules[i];
            if (mod.name === "Bind GUI") continue;
            var bound = mod.key > 0;
            if (show === 0 && !bound) continue;
            if (show === 1 && !bound && !mod.on) continue;
            rows.push({ name: mod.name, key: bound ? keyName(mod.key) : "-", on: mod.on, bound: bound });
        }
        // The point of the fix: an empty panel is indistinguishable from the module being off.
        if (!rows.length) rows.push({ name: "No binds set", key: "-", on: false, bound: false });

        ctx.font = "11px system-ui, sans-serif";
        ctx.textBaseline = "top";
        var widest = 0;
        for (var j = 0; j < rows.length; j++) {
            widest = Math.max(widest, ctx.measureText(rows[j].name + "  " + rows[j].key).width);
        }
        var pw = widest + 12;
        var alignRight = setting(m, "Align").value === 1;
        var x = alignRight ? W - pw - 5 : 5;
        // Left-aligned it shares a corner with the keystrokes, so it stacks above them rather
        // than through them. Right-aligned there is nothing to avoid.
        var floorY = alignRight ? H - 46 : keystrokeTop() - 8;
        var y = floorY - rows.length * 13 - 4;

        if (setting(m, "Background").value) {
            ctx.fillStyle = "rgba(12,14,18,0.58)";
            ctx.fillRect(x, y, pw, rows.length * 13 + 4);
        }
        for (var k = 0; k < rows.length; k++) {
            var r = rows[k];
            ctx.fillStyle = r.on ? "#ffffff" : "#9aa1aa";
            ctx.fillText(r.name, x + 4, y + 2 + k * 13);
            ctx.fillStyle = r.bound ? "#4fd8ff" : "#5a616b";
            var kw = ctx.measureText(r.key).width;
            ctx.fillText(r.key, x + pw - 4 - kw, y + 2 + k * 13);
        }
    }

    function keyName(code) {
        var names = { 82: "R", 86: "V", 33: "PRIOR", 54: "RSHIFT" };
        return names[code] || String(code);
    }

    var KEY_SIZE = 18, KEY_GAP = 2;

    /** Top of the keystroke block, so anything else in that corner can sit clear of it. */
    function keystrokeTop() {
        if (!moduleByName("Keystrokes").on) {
            return H - 46;
        }
        return H - 46 - (KEY_SIZE * 2 + KEY_GAP + Math.round(KEY_SIZE * 0.7) + 8);
    }

    var KEYSTROKE_LAYOUT = [
        { k: "w", x: 1, y: 0 }, { k: "a", x: 0, y: 1 }, { k: "s", x: 1, y: 1 }, { k: "d", x: 2, y: 1 }
    ];

    function drawKeystrokes() {
        if (!moduleByName("Keystrokes").on) return;
        var size = KEY_SIZE, gap = KEY_GAP;
        var ox = 6, oy = keystrokeTop() + 8;
        ctx.font = "600 10px system-ui, sans-serif";
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        for (var i = 0; i < KEYSTROKE_LAYOUT.length; i++) {
            var s = KEYSTROKE_LAYOUT[i];
            var down = !!keys["key" + s.k];
            var bx = ox + s.x * (size + gap), by = oy + s.y * (size + gap);
            ctx.fillStyle = down ? "rgba(255,79,163,0.85)" : "rgba(12,14,18,0.55)";
            ctx.fillRect(bx, by, size, size);
            ctx.fillStyle = down ? "#16060e" : "#e6e9f2";
            ctx.fillText(s.k.toUpperCase(), bx + size / 2, by + size / 2 + 0.5);
        }
        var sw = size * 3 + gap * 2;
        var downSpace = !!keys["space"];
        ctx.fillStyle = downSpace ? "rgba(255,79,163,0.85)" : "rgba(12,14,18,0.55)";
        ctx.fillRect(ox, oy + (size + gap) * 2, sw, size * 0.7);
        ctx.textAlign = "left";
    }

    var swingUntil = 0;

    function drawCrosshair() {
        var m = moduleByName("Crosshair");
        if (!m || !m.on) return;
        var gap = setting(m, "Gap").value;
        var len = setting(m, "Length").value;
        var react = setting(m, "React to hits").value && now < swingUntil;
        var grow = react ? 3 : 0;
        ctx.strokeStyle = react ? "#ff4fa3" : "rgba(255,255,255,0.85)";
        ctx.lineWidth = 2;
        var cx = W / 2, cy = H / 2;
        ctx.beginPath();
        ctx.moveTo(cx, cy - gap - grow); ctx.lineTo(cx, cy - gap - grow - len);
        ctx.moveTo(cx, cy + gap + grow); ctx.lineTo(cx, cy + gap + grow + len);
        ctx.moveTo(cx - gap - grow, cy); ctx.lineTo(cx - gap - grow - len, cy);
        ctx.moveTo(cx + gap + grow, cy); ctx.lineTo(cx + gap + grow + len, cy);
        ctx.stroke();
    }

    function drawHotbar() {
        var slots = 9, size = 20, gap = 2;
        var total = slots * size + (slots - 1) * gap;
        var x = (W - total) / 2, y = H - size - 8;
        for (var i = 0; i < slots; i++) {
            ctx.fillStyle = "rgba(12,14,18,0.5)";
            ctx.fillRect(x + i * (size + gap), y, size, size);
            ctx.strokeStyle = i === heldSlot ? "#ffffff" : "rgba(255,255,255,0.2)";
            ctx.lineWidth = i === heldSlot ? 2 : 1;
            ctx.strokeRect(x + i * (size + gap) + 0.5, y + 0.5, size - 1, size - 1);
        }
    }

    var heldSlot = 0;

    /* -------------------------------------------------------------- click gui */

    var gui = {
        open: false,
        x: 60, y: 60,
        cat: "Render",
        expanded: Object.create(null),
        drag: null,
        mouseX: 0, mouseY: 0,
        hot: null
    };

    var GUI_W = 340, GUI_HEAD = 30, GUI_TAB = 24, GUI_ROW = 22, GUI_SET = 20;

    function guiModules() {
        var out = [];
        for (var i = 0; i < modules.length; i++) {
            if (modules[i].cat === gui.cat) out.push(modules[i]);
        }
        return out;
    }

    function drawClickGui() {
        if (!gui.open) return;
        var list = guiModules();
        var height = GUI_HEAD + GUI_TAB + 8;
        for (var i = 0; i < list.length; i++) {
            height += GUI_ROW;
            if (gui.expanded[list[i].name]) {
                height += list[i].settings.length * GUI_SET + 4;
            }
        }
        height += 6;

        gui.hot = null;

        // Panel
        ctx.fillStyle = "rgba(10,12,19,0.94)";
        roundedPath(gui.x, gui.y, gui.x + GUI_W, gui.y + height, 8, 8, 8, 8);
        ctx.fill();
        ctx.strokeStyle = "rgba(255,79,163,0.35)";
        ctx.lineWidth = 1;
        ctx.stroke();

        // Header
        ctx.font = "600 13px system-ui, sans-serif";
        ctx.textBaseline = "middle";
        ctx.fillStyle = "#ff4fa3";
        ctx.fillText("mindless", gui.x + 12, gui.y + GUI_HEAD / 2);
        ctx.font = "10px system-ui, sans-serif";
        ctx.fillStyle = "#6b7489";
        ctx.fillText("right shift to close", gui.x + 78, gui.y + GUI_HEAD / 2 + 1);

        // Category tabs
        var tabW = GUI_W / CATEGORIES.length;
        for (var c = 0; c < CATEGORIES.length; c++) {
            var tx = gui.x + c * tabW, ty = gui.y + GUI_HEAD;
            var active = CATEGORIES[c] === gui.cat;
            if (active) {
                ctx.fillStyle = "rgba(255,79,163,0.16)";
                ctx.fillRect(tx, ty, tabW, GUI_TAB);
                ctx.fillStyle = "#ff4fa3";
                ctx.fillRect(tx + 6, ty + GUI_TAB - 2, tabW - 12, 2);
            }
            ctx.font = "600 11px system-ui, sans-serif";
            ctx.fillStyle = active ? "#e6e9f2" : "#7b8499";
            ctx.textAlign = "center";
            ctx.fillText(CATEGORIES[c], tx + tabW / 2, ty + GUI_TAB / 2);
            ctx.textAlign = "left";
            hotspot(tx, ty, tabW, GUI_TAB, { type: "cat", value: CATEGORIES[c] });
        }

        // Module rows
        var y = gui.y + GUI_HEAD + GUI_TAB + 4;
        for (var m = 0; m < list.length; m++) {
            var mod = list[m];
            var hovered = inside(gui.x, y, GUI_W, GUI_ROW);
            if (hovered) {
                ctx.fillStyle = "rgba(255,255,255,0.04)";
                ctx.fillRect(gui.x + 4, y, GUI_W - 8, GUI_ROW);
            }
            ctx.font = "12px system-ui, sans-serif";
            ctx.fillStyle = mod.on ? "#e6e9f2" : "#79839a";
            ctx.fillText(mod.name, gui.x + 14, y + GUI_ROW / 2);

            // Toggle pill
            var pw = 26, ph = 13, px = gui.x + GUI_W - pw - 30, py = y + (GUI_ROW - ph) / 2;
            ctx.fillStyle = mod.on ? "#ff4fa3" : "rgba(255,255,255,0.12)";
            roundedPath(px, py, px + pw, py + ph, ph / 2, ph / 2, ph / 2, ph / 2);
            ctx.fill();
            ctx.fillStyle = mod.on ? "#16060e" : "#8d95a8";
            ctx.beginPath();
            ctx.arc(mod.on ? px + pw - ph / 2 : px + ph / 2, py + ph / 2, ph / 2 - 2, 0, Math.PI * 2);
            ctx.fill();

            if (mod.settings.length) {
                ctx.fillStyle = "#6b7489";
                ctx.font = "9px system-ui, sans-serif";
                ctx.fillText(gui.expanded[mod.name] ? "▾" : "▸", gui.x + GUI_W - 20, y + GUI_ROW / 2 + 1);
                hotspot(gui.x + GUI_W - 26, y, 22, GUI_ROW, { type: "expand", mod: mod });
            }
            hotspot(gui.x, y, GUI_W - 30, GUI_ROW, { type: "toggle", mod: mod });
            y += GUI_ROW;

            if (gui.expanded[mod.name]) {
                for (var s = 0; s < mod.settings.length; s++) {
                    drawSetting(mod.settings[s], gui.x + 22, y, GUI_W - 44);
                    y += GUI_SET;
                }
                y += 4;
            }
        }
    }

    function drawSetting(s, x, y, w) {
        ctx.font = "11px system-ui, sans-serif";
        ctx.textBaseline = "middle";
        var mid = y + GUI_SET / 2;

        if (s.type === "toggle") {
            ctx.fillStyle = "#9ba4ba";
            ctx.fillText(s.label, x, mid);
            var bx = x + w - 12;
            ctx.strokeStyle = s.value ? "#ff4fa3" : "rgba(255,255,255,0.22)";
            ctx.lineWidth = 1;
            ctx.strokeRect(bx + 0.5, mid - 5.5, 11, 11);
            if (s.value) {
                ctx.fillStyle = "#ff4fa3";
                ctx.fillRect(bx + 3, mid - 3, 6, 6);
            }
            hotspot(x, y, w, GUI_SET, { type: "set-toggle", setting: s });
            return;
        }

        if (s.type === "mode") {
            ctx.fillStyle = "#9ba4ba";
            ctx.fillText(s.label, x, mid);
            var text = s.options[s.value];
            var tw = ctx.measureText(text).width;
            ctx.fillStyle = "#ff4fa3";
            ctx.fillText(text, x + w - tw, mid);
            hotspot(x, y, w, GUI_SET, { type: "set-mode", setting: s });
            return;
        }

        var label = s.label;
        var shown = (Math.round(s.value * 100) / 100) + (s.unit ? " " + s.unit : "");
        ctx.fillStyle = "#9ba4ba";
        ctx.fillText(label, x, mid - 5);
        var vw = ctx.measureText(shown).width;
        ctx.fillStyle = "#e6e9f2";
        ctx.fillText(shown, x + w - vw, mid - 5);

        var trackY = mid + 5;
        ctx.fillStyle = "rgba(255,255,255,0.12)";
        ctx.fillRect(x, trackY - 1, w, 2);
        var frac = (s.value - s.min) / (s.max - s.min);
        ctx.fillStyle = "#ff4fa3";
        ctx.fillRect(x, trackY - 1, w * frac, 2);
        ctx.beginPath();
        ctx.arc(x + w * frac, trackY, 4, 0, Math.PI * 2);
        ctx.fill();
        hotspot(x, y, w, GUI_SET, { type: "set-slider", setting: s, x: x, w: w });
    }

    function inside(x, y, w, h) {
        return gui.mouseX >= x && gui.mouseX <= x + w && gui.mouseY >= y && gui.mouseY <= y + h;
    }

    function hotspot(x, y, w, h, payload) {
        if (inside(x, y, w, h)) gui.hot = payload;
    }

    /* ------------------------------------------------------------------ input */

    function code(e) {
        if (e.code === "Space") return "space";
        if (e.code.indexOf("Key") === 0) return "key" + e.code.slice(3).toLowerCase();
        if (e.code === "ShiftLeft") return "sneak";
        if (e.code === "ControlLeft") return "sprint";
        return e.code.toLowerCase();
    }

    window.addEventListener("keydown", function (e) {
        if (e.code === "ShiftRight") {
            gui.open = !gui.open;
            if (gui.open) document.exitPointerLock();
            e.preventDefault();
            return;
        }
        if (e.code === "Escape" && gui.open) { gui.open = false; e.preventDefault(); return; }
        if (e.code.indexOf("Digit") === 0) {
            var n = parseInt(e.code.slice(5), 10);
            if (n >= 1 && n <= 9) heldSlot = n - 1;
        }
        keys[code(e)] = true;
        if (e.code === "Space" || e.code.indexOf("Key") === 0) e.preventDefault();
    });

    window.addEventListener("keyup", function (e) { keys[code(e)] = false; });

    game.addEventListener("click", function () {
        if (gui.open) return;
        if (!locked) view.requestPointerLock();
    });

    document.addEventListener("pointerlockchange", function () {
        locked = document.pointerLockElement === view;
        curtain.hidden = locked || gui.open;
        game.classList.toggle("released", !locked);
    });

    document.addEventListener("mousemove", function (e) {
        if (locked && !gui.open) {
            cam.yaw += e.movementX * 0.0022;
            cam.pitch -= e.movementY * 0.0022;
            var lim = Math.PI / 2.6;
            if (cam.pitch > lim) cam.pitch = lim;
            if (cam.pitch < -lim) cam.pitch = -lim;
        }
        var r = view.getBoundingClientRect();
        gui.mouseX = (e.clientX - r.left) * (W / r.width);
        gui.mouseY = (e.clientY - r.top) * (H / r.height);
        if (gui.drag) {
            if (gui.drag.type === "panel") {
                gui.x = gui.mouseX - gui.drag.dx;
                gui.y = gui.mouseY - gui.drag.dy;
            } else if (gui.drag.type === "slider") {
                applySlider(gui.drag.payload, gui.mouseX);
            }
        }
    });

    function applySlider(p, mx) {
        var s = p.setting;
        var frac = Math.max(0, Math.min(1, (mx - p.x) / p.w));
        var raw = s.min + frac * (s.max - s.min);
        s.value = Math.round(raw / s.step) * s.step;
        s.value = Math.max(s.min, Math.min(s.max, Math.round(s.value * 1000) / 1000));
    }

    window.addEventListener("mousedown", function (e) {
        if (!gui.open) {
            if (locked && e.button === 0) swing();
            return;
        }
        var hot = gui.hot;
        if (!hot) {
            if (inside(gui.x, gui.y, GUI_W, GUI_HEAD)) {
                gui.drag = { type: "panel", dx: gui.mouseX - gui.x, dy: gui.mouseY - gui.y };
            }
            return;
        }
        if (hot.type === "cat") gui.cat = hot.value;
        else if (hot.type === "expand") gui.expanded[hot.mod.name] = !gui.expanded[hot.mod.name];
        else if (hot.type === "toggle") hot.mod.on = !hot.mod.on;
        else if (hot.type === "set-toggle") hot.setting.value = !hot.setting.value;
        else if (hot.type === "set-mode") {
            var dir = e.button === 2 ? -1 : 1;
            var len = hot.setting.options.length;
            hot.setting.value = (hot.setting.value + dir + len) % len;
        } else if (hot.type === "set-slider") {
            gui.drag = { type: "slider", payload: hot };
            applySlider(hot, gui.mouseX);
        }
        e.preventDefault();
    });

    window.addEventListener("mouseup", function () { gui.drag = null; });
    window.addEventListener("contextmenu", function (e) { if (gui.open) e.preventDefault(); });

    function swing() {
        swingUntil = now + 180;
        // Fire the hit effect at whatever the crosshair is pointing at, three blocks out, which
        // is close enough to vanilla reach for judging how the wave reads.
        var reach = val("Kill Aura", "Range", 3.0);
        var tx = cam.x + Math.cos(cam.yaw) * reach;
        var tz = cam.z + Math.sin(cam.yaw) * reach;
        hitEffect(tx, 0, tz);
    }

    /* ----------------------------------------------------------------- update */

    function update(dt) {
        var speed = (cam.sprinting ? 5.6 : 4.3) * (cam.sneaking ? 0.3 : 1) * dt;
        var fwd = (keys.keyw ? 1 : 0) - (keys.keys ? 1 : 0);
        var strafe = (keys.keyd ? 1 : 0) - (keys.keya ? 1 : 0);
        cam.sneaking = !!keys.sneak;
        cam.sprinting = !!keys.sprint && fwd > 0;

        if (fwd || strafe) {
            var len = Math.hypot(fwd, strafe);
            var mx = (Math.cos(cam.yaw) * fwd - Math.sin(cam.yaw) * strafe) / len * speed;
            var mz = (Math.sin(cam.yaw) * fwd + Math.cos(cam.yaw) * strafe) / len * speed;
            if (!at(Math.floor(cam.x + mx * 3), Math.floor(cam.z))) cam.x += mx;
            if (!at(Math.floor(cam.x), Math.floor(cam.z + mz * 3))) cam.z += mz;
            cam.bob = Math.sin(now / 110) * (cam.sprinting ? 1.6 : 1.0);
        } else {
            cam.bob *= 0.85;
        }

        if (keys.space && cam.onGround) {
            cam.vy = 8.4;
            cam.onGround = false;
            jumpEffect(cam.x, 0, cam.z);
        }
        if (!cam.onGround) {
            cam.vy -= 26 * dt;
            cam.y += cam.vy * dt;
            if (cam.y <= 0) { cam.y = 0; cam.vy = 0; cam.onGround = true; }
        }

        for (var i = effects.length - 1; i >= 0; i--) {
            if (now - effects[i].born > effects[i].life) effects.splice(i, 1);
        }
    }

    /* ------------------------------------------------------------------- loop */

    var now = performance.now();
    var last = now;
    var frames = 0, fpsAt = now, fps = 0;

    var elFps = document.getElementById("s-fps");
    var elPos = document.getElementById("s-pos");
    var elFace = document.getElementById("s-face");
    var elFx = document.getElementById("s-fx");
    var elMods = document.getElementById("s-mods");

    function facing() {
        var d = ((cam.yaw * 180 / Math.PI) % 360 + 360) % 360;
        if (d < 45 || d >= 315) return "east";
        if (d < 135) return "south";
        if (d < 225) return "west";
        return "north";
    }

    function frame(ts) {
        now = ts;
        var dt = Math.min(0.05, (ts - last) / 1000);
        last = ts;

        update(dt);

        ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
        renderWorld();
        renderEffects();

        ctx.textAlign = "left";
        drawWatermark();
        drawArrayList();
        drawBindList();
        drawKeystrokes();
        drawHotbar();
        drawCrosshair();
        drawClickGui();

        frames++;
        if (ts - fpsAt > 500) {
            fps = Math.round(frames * 1000 / (ts - fpsAt));
            frames = 0;
            fpsAt = ts;
            elFps.textContent = fps;
            elPos.textContent = cam.x.toFixed(1) + " " + (cam.y + 1).toFixed(1) + " " + cam.z.toFixed(1);
            elFace.textContent = facing();
            elFx.textContent = effects.length;
            var on = 0;
            for (var i = 0; i < modules.length; i++) if (modules[i].on) on++;
            elMods.textContent = on + " / " + modules.length;
        }

        requestAnimationFrame(frame);
    }

    buildWorld();
    resize();
    requestAnimationFrame(frame);
}());
