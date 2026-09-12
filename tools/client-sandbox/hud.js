/*
 * Everything drawn flat over the world: Minecraft's own HUD, and the client's.
 *
 * The client half is transcribed from the Java rather than approximated. The array list runs
 * HUD.paintBackgroundShapes, inverse fillets at width changes included, so a radius chosen here
 * is the radius to put in the slider. The Minecraft half is here to give it something honest to
 * sit against: a hotbar and a health row are most of what a HUD has to coexist with.
 */
(function (MS) {
    "use strict";

    var HUD = MS.HUD = {};
    var ctx = null, W = 0, H = 0;

    HUD.attach = function (context) { ctx = context; };
    HUD.size = function (w, h) { W = w; H = h; };

    var FONT = '11px "Minecraftia", "Segoe UI", system-ui, sans-serif';
    var FONT_SMALL = '9px "Minecraftia", "Segoe UI", system-ui, sans-serif';

    function shadowText(text, x, y, color) {
        ctx.fillStyle = "rgba(0,0,0,0.55)";
        ctx.fillText(text, x + 1, y + 1);
        ctx.fillStyle = color;
        ctx.fillText(text, x, y);
    }

    HUD.shadowText = shadowText;

    /* ------------------------------------------------------- minecraft chrome */

    function heart(x, y, full) {
        ctx.fillStyle = "rgba(20,20,20,0.5)";
        ctx.fillRect(x, y, 9, 9);
        ctx.fillStyle = full > 0.5 ? "#d63a35" : (full > 0 ? "#8e2a26" : "#3a3a3a");
        ctx.fillRect(x + 1, y + 2, 3, 2);
        ctx.fillRect(x + 5, y + 2, 3, 2);
        ctx.fillRect(x + 1, y + 4, 7, 2);
        ctx.fillRect(x + 2, y + 6, 5, 1);
        ctx.fillRect(x + 3, y + 7, 3, 1);
    }

    function shank(x, y, full) {
        ctx.fillStyle = "rgba(20,20,20,0.5)";
        ctx.fillRect(x, y, 9, 9);
        ctx.fillStyle = full > 0.5 ? "#a2701f" : (full > 0 ? "#6d4b16" : "#3a3a3a");
        ctx.fillRect(x + 1, y + 1, 7, 4);
        ctx.fillRect(x + 2, y + 5, 5, 2);
        ctx.fillRect(x + 4, y + 7, 2, 1);
    }

    HUD.drawVanilla = function (state) {
        var slotSize = 22, slots = 9, gap = 0;
        var barW = slots * slotSize;
        var x0 = Math.round((W - barW) / 2);
        var y0 = H - slotSize - 6;

        // XP bar
        var xpW = barW, xpY = y0 - 8;
        ctx.fillStyle = "rgba(20,20,20,0.65)";
        ctx.fillRect(x0, xpY, xpW, 5);
        ctx.fillStyle = "#7ee02a";
        ctx.fillRect(x0, xpY, xpW * state.xp, 5);
        ctx.font = FONT_SMALL;
        ctx.textAlign = "center";
        shadowText(String(state.level), W / 2, xpY - 1, "#7ee02a");
        ctx.textAlign = "left";

        // Health and hunger sit on the two rows above the bar, as they do in game.
        var rowY = xpY - 12;
        for (var i = 0; i < 10; i++) {
            heart(x0 + i * 9, rowY, state.health / 2 - i);
            shank(x0 + barW - 9 - i * 9, rowY, state.hunger / 2 - i);
        }
        if (state.armor > 0) {
            for (var a = 0; a < 10; a++) {
                var on = state.armor / 2 - a > 0;
                ctx.fillStyle = on ? "#c9ced6" : "rgba(20,20,20,0.5)";
                ctx.fillRect(x0 + a * 9 + 1, rowY - 10, 7, 7);
            }
        }

        // Hotbar
        ctx.fillStyle = "rgba(16,16,16,0.62)";
        ctx.fillRect(x0, y0, barW, slotSize);
        for (var s = 0; s < slots; s++) {
            var sx = x0 + s * slotSize;
            ctx.strokeStyle = "rgba(255,255,255,0.10)";
            ctx.lineWidth = 1;
            ctx.strokeRect(sx + 0.5, y0 + 0.5, slotSize - 1, slotSize - 1);
            var item = state.hotbar[s];
            if (item) {
                ctx.fillStyle = item.color;
                ctx.fillRect(sx + 4, y0 + 4, slotSize - 8, slotSize - 8);
                ctx.fillStyle = "rgba(0,0,0,0.25)";
                ctx.fillRect(sx + 4, y0 + slotSize - 8, slotSize - 8, 4);
                if (item.count > 1) {
                    ctx.font = FONT_SMALL;
                    ctx.textAlign = "right";
                    shadowText(String(item.count), sx + slotSize - 3, y0 + slotSize - 11, "#ffffff");
                    ctx.textAlign = "left";
                }
            }
        }
        var selX = x0 + state.slot * slotSize;
        ctx.strokeStyle = "#ffffff";
        ctx.lineWidth = 2;
        ctx.strokeRect(selX - 1, y0 - 1, slotSize + 2, slotSize + 2);

        // The held item's name fades out above the bar after a slot change, as it does in game.
        if (state.itemNameUntil > state.now) {
            var fade = Math.min(1, (state.itemNameUntil - state.now) / 400);
            var item2 = state.hotbar[state.slot];
            if (item2) {
                ctx.globalAlpha = fade;
                ctx.font = FONT;
                ctx.textAlign = "center";
                shadowText(item2.name, W / 2, rowY - 24, "#ffffff");
                ctx.textAlign = "left";
                ctx.globalAlpha = 1;
            }
        }
    };

    HUD.drawCrosshair = function (state) {
        var m = state.module("Crosshair");
        if (!m || !m.on) {
            return;
        }
        if (state.perspective !== 0) {
            return;
        }
        var gap = state.setting(m, "Gap").value;
        var len = state.setting(m, "Length").value;
        var react = state.setting(m, "React to hits").value && state.now < state.swingUntil;
        var grow = react ? 3 : 0;
        var cx = Math.round(W / 2), cy = Math.round(H / 2);
        ctx.save();
        ctx.globalCompositeOperation = "difference";
        ctx.strokeStyle = react ? "#ff4fa3" : "#ffffff";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(cx, cy - gap - grow); ctx.lineTo(cx, cy - gap - grow - len);
        ctx.moveTo(cx, cy + gap + grow); ctx.lineTo(cx, cy + gap + grow + len);
        ctx.moveTo(cx - gap - grow, cy); ctx.lineTo(cx - gap - grow - len, cy);
        ctx.moveTo(cx + gap + grow, cy); ctx.lineTo(cx + gap + grow + len, cy);
        ctx.stroke();
        ctx.restore();
    };

    /* ---------------------------------------------------------- array list */

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

    HUD.roundedPath = roundedPath;

    // The crook of a reflex corner: the corner square with a quarter disc taken out of it. This
    // is the piece a plain quarter disc wrongly removes, and its absence is what made the ragged
    // edge of a connected list read as a string of beads.
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

    function hudColor(state, offset, mod) {
        var m = state.module("HUD");
        var mode = state.setting(m, "Color mode").value;
        if (mode === 0) {
            var c = state.setting(m, "Color").value;
            return "rgb(" + c[0] + "," + c[1] + "," + c[2] + ")";
        }
        var speed = state.setting(m, "Wave speed").value;
        var hue = ((state.now * 0.02 * speed + offset * 12) % 360 + 360) % 360;
        if (mode === 2) {
            return "hsl(" + hue + ", 78%, 66%)";
        }
        var a = state.setting(m, "Color").value;
        var b = state.setting(m, "Color 2").value;
        var t = (Math.sin((state.now * 0.002 * speed) + offset * 0.35) + 1) / 2;
        return "rgb(" + Math.round(a[0] + (b[0] - a[0]) * t) + ","
            + Math.round(a[1] + (b[1] - a[1]) * t) + ","
            + Math.round(a[2] + (b[2] - a[2]) * t) + ")";
    }

    HUD.drawArrayList = function (state) {
        var m = state.module("HUD");
        if (!m || !m.on) return;

        var scale = state.setting(m, "Font size").value;
        ctx.font = Math.round(11 * scale) + 'px "Segoe UI", system-ui, sans-serif';
        ctx.textBaseline = "top";

        var pad = Math.round(3 * scale);
        var rowH = Math.round(12 * scale) + state.setting(m, "Line spacing").value;

        var rows = [];
        var mods = state.modules;
        for (var i = 0; i < mods.length; i++) {
            if (!mods[i].on || mods[i].name === "HUD" || mods[i].hidden) continue;
            var label = mods[i].name;
            if (state.setting(m, "Lowercase").value) label = label.toLowerCase();
            var suffix = mods[i].suffix ? mods[i].suffix(state) : "";
            rows.push({ text: label, suffix: suffix, w: Math.round(ctx.measureText(label + suffix).width) });
        }
        if (!rows.length) return;
        rows.sort(function (a, b) { return b.w - a.w; });

        var right = state.setting(m, "Align right").value;
        var anchorX = right ? W - 5 : 5;
        var top = 22;

        var mode = state.setting(m, "Background mode").value;
        var rounded = state.setting(m, "Rounded background").value;
        var radiusSetting = rounded ? state.setting(m, "Corner radius").value : 0;
        var stepPct = state.setting(m, "Step rounding").value / 100;
        var alpha = state.setting(m, "Background opacity").value / 100;

        var narrowest = Infinity;
        for (var n = 0; n < rows.length; n++) narrowest = Math.min(narrowest, rows[n].w + pad * 2);
        var radius = Math.max(0, Math.min(radiusSetting, Math.min(rowH, narrowest * 0.5)));
        var transition = Math.min(radius * stepPct, rowH * 0.5);

        if (state.setting(m, "Draw background").value) {
            ctx.fillStyle = "rgba(0,0,0," + alpha.toFixed(3) + ")";
            if (mode === 2) {
                var maxW = 0;
                for (var q = 0; q < rows.length; q++) maxW = Math.max(maxW, rows[q].w);
                var pl = right ? anchorX - maxW - pad : anchorX - pad;
                roundedPath(pl, top, pl + maxW + pad * 2, top + rows.length * rowH, radius, radius, radius, radius);
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

            if (state.setting(m, "Row separators").value && mode === 0 && rows.length > 1) {
                ctx.fillStyle = "rgba(255,255,255,0.10)";
                for (var s = 0; s + 1 < rows.length; s++) {
                    var shared = Math.min(rows[s].w, rows[s + 1].w) + pad * 2;
                    var sx = right ? anchorX + pad - shared : anchorX - pad;
                    ctx.fillRect(sx, top + (s + 1) * rowH - 0.5, shared, 1);
                }
            }
        }

        var shadow = state.setting(m, "Text shadow").value;
        for (var t2 = 0; t2 < rows.length; t2++) {
            var color = hudColor(state, t2, null);
            var tx = right ? anchorX - rows[t2].w : anchorX;
            var ty = top + t2 * rowH + Math.round((rowH - 11 * scale) / 2);
            if (shadow) {
                ctx.fillStyle = "rgba(0,0,0,0.5)";
                ctx.fillText(rows[t2].text + rows[t2].suffix, tx + 1, ty + 1);
            }
            ctx.fillStyle = color;
            ctx.fillText(rows[t2].text, tx, ty);
            if (rows[t2].suffix) {
                ctx.fillStyle = "rgba(190,196,210,0.85)";
                ctx.fillText(rows[t2].suffix, tx + ctx.measureText(rows[t2].text).width, ty);
            }
        }
    };

    /* ------------------------------------------------------------ other panels */

    HUD.drawWatermark = function (state) {
        var m = state.module("Watermark");
        if (!m || !m.on) return;
        ctx.font = '600 14px "Segoe UI", system-ui, sans-serif';
        ctx.textBaseline = "top";
        shadowText("mindless", 5, 5, "#ff4fa3");
        ctx.font = FONT_SMALL;
        shadowText(state.user + "  " + state.fps + " fps", 5 + ctx.measureText("mindless").width + 46, 9, "rgba(215,220,232,0.8)");
    };

    HUD.drawBindList = function (state) {
        var m = state.module("Bind GUI");
        if (!m || !m.on) return;
        var show = state.setting(m, "Show").value;
        var rows = [];
        for (var i = 0; i < state.modules.length; i++) {
            var mod = state.modules[i];
            if (mod.name === "Bind GUI" || mod.hidden) continue;
            var bound = mod.key > 0;
            if (show === 0 && !bound) continue;
            if (show === 1 && !bound && !mod.on) continue;
            rows.push({ name: mod.name, key: bound ? state.keyName(mod.key) : "-", on: mod.on, bound: bound });
        }
        if (!rows.length) rows.push({ name: "No binds set", key: "-", on: false, bound: false });

        ctx.font = FONT;
        ctx.textBaseline = "top";
        var widest = 0;
        for (var j = 0; j < rows.length; j++) {
            widest = Math.max(widest, ctx.measureText(rows[j].name + "   " + rows[j].key).width);
        }
        var pw = widest + 12;
        var alignRight = state.setting(m, "Align").value === 1;
        var x = alignRight ? W - pw - 5 : 5;
        var floorY = alignRight ? H - 64 : state.keystrokeTop - 8;
        var y = floorY - rows.length * 13 - 4;

        if (state.setting(m, "Background").value) {
            ctx.fillStyle = "rgba(12,14,18,0.55)";
            roundedPath(x, y, x + pw, y + rows.length * 13 + 4, 3, 3, 3, 3);
            ctx.fill();
        }
        for (var k = 0; k < rows.length; k++) {
            var r = rows[k];
            shadowText(r.name, x + 4, y + 2 + k * 13, r.on ? "#ffffff" : "#9aa1aa");
            var kw = ctx.measureText(r.key).width;
            shadowText(r.key, x + pw - 4 - kw, y + 2 + k * 13, r.bound ? "#4fd8ff" : "#5a616b");
        }
    };

    var KEY_SIZE = 20, KEY_GAP = 2;

    HUD.keystrokeTop = function (state) {
        var m = state.module("Keystrokes");
        if (!m || !m.on) return H - 64;
        return H - 64 - (KEY_SIZE * 2 + KEY_GAP * 2 + Math.round(KEY_SIZE * 0.6));
    };

    HUD.drawKeystrokes = function (state) {
        var m = state.module("Keystrokes");
        if (!m || !m.on) return;
        var oy = HUD.keystrokeTop(state);
        var ox = 5;
        var layout = [
            { k: "w", label: "W", x: 1, y: 0 }, { k: "a", label: "A", x: 0, y: 1 },
            { k: "s", label: "S", x: 1, y: 1 }, { k: "d", label: "D", x: 2, y: 1 }
        ];
        ctx.font = '600 10px "Segoe UI", system-ui, sans-serif';
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        var showCps = state.setting(m, "Show CPS").value;

        for (var i = 0; i < layout.length; i++) {
            var s = layout[i];
            var down = !!state.keys["key" + s.k];
            key(ox + s.x * (KEY_SIZE + KEY_GAP), oy + s.y * (KEY_SIZE + KEY_GAP), KEY_SIZE, KEY_SIZE, s.label, down);
        }
        var wide = KEY_SIZE * 3 + KEY_GAP * 2;
        var rowY = oy + (KEY_SIZE + KEY_GAP) * 2;
        key(ox, rowY, wide, Math.round(KEY_SIZE * 0.6), "", !!state.keys.space);
        ctx.textAlign = "left";
        ctx.textBaseline = "top";

        if (showCps) {
            ctx.font = FONT_SMALL;
            ctx.textAlign = "center";
            shadowText(state.cps + " cps", ox + wide / 2, rowY + Math.round(KEY_SIZE * 0.6) + 3, "#ffffff");
            ctx.textAlign = "left";
        }
    };

    function key(x, y, w, h, label, down) {
        ctx.fillStyle = down ? "rgba(255,79,163,0.82)" : "rgba(14,16,22,0.55)";
        roundedPath(x, y, x + w, y + h, 3, 3, 3, 3);
        ctx.fill();
        if (label) {
            ctx.fillStyle = down ? "#190711" : "#e6e9f2";
            ctx.fillText(label, x + w / 2, y + h / 2 + 0.5);
        }
    }

    /* ----------------------------------------------------------------- chat */

    HUD.drawChat = function (state) {
        if (!state.chat.length) return;
        ctx.font = FONT;
        ctx.textBaseline = "top";
        var lineH = 12;
        var y = state.keystrokeTop - 12 - Math.min(state.chat.length, 8) * lineH;
        var shown = state.chat.slice(-8);
        for (var i = 0; i < shown.length; i++) {
            var age = state.now - shown[i].at;
            if (age > 9000 && !state.chatOpen) continue;
            var fade = state.chatOpen ? 1 : Math.min(1, (9000 - age) / 1200);
            ctx.globalAlpha = Math.max(0, fade);
            var w = ctx.measureText(shown[i].text).width;
            ctx.fillStyle = "rgba(0,0,0,0.42)";
            ctx.fillRect(2, y + i * lineH - 1, w + 6, lineH);
            shadowText(shown[i].text, 4, y + i * lineH, "#ffffff");
            ctx.globalAlpha = 1;
        }
    };

    /* ------------------------------------------------------------ f3 debug */

    HUD.drawDebug = function (state) {
        ctx.font = FONT;
        ctx.textBaseline = "top";
        var left = [
            "Mindless sandbox (" + state.fps + " fps, " + state.triangles + " tris)",
            "XYZ: " + state.p.x.toFixed(3) + " / " + state.p.y.toFixed(3) + " / " + state.p.z.toFixed(3),
            "Block: " + Math.floor(state.p.x) + " " + Math.floor(state.p.y) + " " + Math.floor(state.p.z),
            "Facing: " + state.facing + " (" + state.yawDeg.toFixed(1) + " / " + state.pitchDeg.toFixed(1) + ")",
            "Perspective: " + ["first person", "third back", "third front"][state.perspective],
            "Looking at: " + (state.looking || "nothing"),
            "Effects: " + state.effectCount + "   Modules: " + state.enabledCount + "/" + state.modules.length
        ];
        for (var i = 0; i < left.length; i++) {
            var w = ctx.measureText(left[i]).width;
            ctx.fillStyle = "rgba(0,0,0,0.45)";
            ctx.fillRect(2, 2 + i * 12, w + 4, 12);
            ctx.fillStyle = "#e6e9f2";
            ctx.fillText(left[i], 4, 4 + i * 12);
        }
    };

    /* --------------------------------------------------------------- vignette */

    HUD.drawOverlayTint = function (state) {
        if (state.hurtUntil > state.now) {
            ctx.fillStyle = "rgba(180,20,20," + (0.35 * (state.hurtUntil - state.now) / 450).toFixed(3) + ")";
            ctx.fillRect(0, 0, W, H);
        }
    };

}(window.MS = window.MS || {}));
