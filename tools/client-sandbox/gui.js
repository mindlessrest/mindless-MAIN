/*
 * The click GUI.
 *
 * Laid out the way the real one is: a draggable panel, categories down the left, modules in a
 * scrolling column, and settings that expand under the module they belong to. It is themed from
 * its own settings rather than from constants, because the point of having it here is to try the
 * theme on and see it over the game instead of guessing at hex values in a file.
 *
 * Everything is hit-tested against rectangles recorded while drawing. One pass builds the picture
 * and the hit regions together, so a control can never be drawn in one place and clickable in
 * another, which is the usual way an immediate-mode GUI rots.
 */
(function (MS) {
    "use strict";

    var GUI = MS.GUI = {};
    var ctx = null, W = 0, H = 0;

    GUI.attach = function (context) { ctx = context; };
    GUI.size = function (w, h) { W = w; H = h; };

    var state = GUI.state = {
        open: false,
        x: 90, y: 70,
        cat: "Render",
        scroll: 0,
        expanded: Object.create(null),
        drag: null,
        binding: null,
        mouseX: 0, mouseY: 0,
        down: false,
        search: "",
        searching: false
    };

    var regions = [];

    var PANEL_W = 420, PANEL_H = 300;
    var SIDE_W = 96, HEAD_H = 30, SEARCH_H = 22, ROW_H = 24, SET_H = 21;

    function theme(key) {
        return MS.sandbox.setting(MS.sandbox.module("Click GUI"), key).value;
    }

    function rgba(c, a) {
        return "rgba(" + c[0] + "," + c[1] + "," + c[2] + "," + a + ")";
    }

    function region(x, y, w, h, payload) {
        regions.push({ x: x, y: y, w: w, h: h, p: payload });
    }

    function hovered(x, y, w, h) {
        return state.mouseX >= x && state.mouseX <= x + w && state.mouseY >= y && state.mouseY <= y + h;
    }

    function roundRect(x, y, w, h, r) {
        MS.HUD.roundedPath(x, y, x + w, y + h, r, r, r, r);
    }

    GUI.draw = function (sandbox) {
        if (!state.open) return;
        regions = [];

        var accent = theme("Accent");
        var panelAlpha = theme("Opacity") / 100;
        var radius = theme("Rounding");
        var px = state.x, py = state.y;
        var scale = theme("Scale");
        var pw = Math.round(PANEL_W * scale), ph = Math.round(PANEL_H * scale);

        ctx.save();

        // Behind the panel the world is dimmed, the way opening an inventory dims it. Without it
        // a translucent panel over bright terrain is unreadable, which is the single most common
        // complaint about a glass theme.
        var dim = theme("Dim background") / 100;
        if (dim > 0) {
            ctx.fillStyle = "rgba(4,6,11," + dim.toFixed(3) + ")";
            ctx.fillRect(0, 0, W, H);
        }

        // Panel
        ctx.fillStyle = "rgba(9,11,18," + panelAlpha.toFixed(3) + ")";
        roundRect(px, py, pw, ph, radius);
        ctx.fill();
        if (theme("Border")) {
            ctx.strokeStyle = rgba(accent, 0.45);
            ctx.lineWidth = 1;
            roundRect(px + 0.5, py + 0.5, pw - 1, ph - 1, radius);
            ctx.stroke();
        }

        // Header
        ctx.font = '600 14px "Segoe UI", system-ui, sans-serif';
        ctx.textBaseline = "middle";
        ctx.fillStyle = rgba(accent, 1);
        ctx.fillText("mindless", px + 14, py + HEAD_H / 2);
        ctx.font = '10px "Segoe UI", system-ui, sans-serif';
        ctx.fillStyle = "rgba(150,158,176,0.8)";
        ctx.fillText(sandbox.user, px + 14 + ctx.measureText("mindless").width + 46, py + HEAD_H / 2 + 1);
        ctx.textAlign = "right";
        ctx.fillText("right shift", px + pw - 12, py + HEAD_H / 2 + 1);
        ctx.textAlign = "left";
        region(px, py, pw, HEAD_H, { type: "header" });

        ctx.fillStyle = "rgba(255,255,255,0.06)";
        ctx.fillRect(px, py + HEAD_H, pw, 1);

        // Category rail
        var sideW = Math.round(SIDE_W * scale);
        var cats = sandbox.categories;
        var cy = py + HEAD_H + 6;
        for (var c = 0; c < cats.length; c++) {
            var active = cats[c] === state.cat;
            var rx = px + 6, ry = cy + c * (ROW_H + 2), rw = sideW - 12, rh = ROW_H;
            if (active) {
                ctx.fillStyle = rgba(accent, 0.16);
                roundRect(rx, ry, rw, rh, radius - 2);
                ctx.fill();
                ctx.fillStyle = rgba(accent, 1);
                ctx.fillRect(rx, ry + 4, 2, rh - 8);
            } else if (hovered(rx, ry, rw, rh)) {
                ctx.fillStyle = "rgba(255,255,255,0.04)";
                roundRect(rx, ry, rw, rh, radius - 2);
                ctx.fill();
            }
            ctx.font = '600 11px "Segoe UI", system-ui, sans-serif';
            ctx.fillStyle = active ? "#e9ecf4" : "#7c8598";
            ctx.fillText(cats[c], rx + 10, ry + rh / 2);
            region(rx, ry, rw, rh, { type: "cat", value: cats[c] });
        }

        ctx.fillStyle = "rgba(255,255,255,0.06)";
        ctx.fillRect(px + sideW, py + HEAD_H, 1, ph - HEAD_H);

        // Search
        var listX = px + sideW + 8;
        var listW = pw - sideW - 16;
        var sy = py + HEAD_H + 6;
        ctx.fillStyle = state.searching ? "rgba(255,255,255,0.08)" : "rgba(255,255,255,0.04)";
        roundRect(listX, sy, listW, SEARCH_H, radius - 2);
        ctx.fill();
        ctx.font = '11px "Segoe UI", system-ui, sans-serif';
        ctx.fillStyle = state.search ? "#e9ecf4" : "#666f82";
        var shown = state.search || "search";
        ctx.fillText(shown + (state.searching && Math.floor(sandbox.now / 500) % 2 === 0 ? "|" : ""),
            listX + 9, sy + SEARCH_H / 2);
        region(listX, sy, listW, SEARCH_H, { type: "search" });

        // Module list, clipped so long categories scroll instead of spilling.
        var listY = sy + SEARCH_H + 6;
        var listH = ph - (listY - py) - 8;
        ctx.save();
        ctx.beginPath();
        ctx.rect(listX, listY, listW, listH);
        ctx.clip();

        var mods = sandbox.modulesIn(state.cat, state.search);
        var y = listY - state.scroll;
        var contentH = 0;

        for (var i = 0; i < mods.length; i++) {
            var mod = mods[i];
            var rowTop = y;
            drawModuleRow(sandbox, mod, listX, y, listW, accent, radius);
            y += ROW_H;
            contentH += ROW_H;
            if (state.expanded[mod.name]) {
                for (var s = 0; s < mod.settings.length; s++) {
                    var set = mod.settings[s];
                    if (set.visibleIf && !set.visibleIf(mod)) continue;
                    drawSetting(sandbox, set, listX + 12, y, listW - 24, accent);
                    y += SET_H;
                    contentH += SET_H;
                }
                y += 4;
                contentH += 4;
            }
            if (rowTop > listY + listH) break;
        }
        state.contentH = contentH;
        state.viewH = listH;
        ctx.restore();

        // Scrollbar, only when there is something to scroll.
        if (contentH > listH) {
            var barH = Math.max(24, listH * (listH / contentH));
            var barY = listY + (listH - barH) * (state.scroll / Math.max(1, contentH - listH));
            ctx.fillStyle = "rgba(255,255,255,0.05)";
            ctx.fillRect(px + pw - 5, listY, 3, listH);
            ctx.fillStyle = rgba(accent, 0.5);
            ctx.fillRect(px + pw - 5, barY, 3, barH);
        }

        if (state.binding) {
            ctx.fillStyle = "rgba(4,6,11,0.72)";
            ctx.fillRect(0, 0, W, H);
            ctx.font = '600 16px "Segoe UI", system-ui, sans-serif';
            ctx.textAlign = "center";
            ctx.fillStyle = "#e9ecf4";
            ctx.fillText("Press a key to bind " + state.binding.name, W / 2, H / 2 - 10);
            ctx.font = '12px "Segoe UI", system-ui, sans-serif';
            ctx.fillStyle = "#8d95a8";
            ctx.fillText("Escape clears it", W / 2, H / 2 + 12);
            ctx.textAlign = "left";
        }

        ctx.restore();
    };

    function drawModuleRow(sandbox, mod, x, y, w, accent, radius) {
        var hot = hovered(x, y, w, ROW_H) && !state.drag;
        if (hot) {
            ctx.fillStyle = "rgba(255,255,255,0.04)";
            roundRect(x, y, w, ROW_H, radius - 2);
            ctx.fill();
        }
        if (mod.on) {
            ctx.fillStyle = rgba(accent, 0.9);
            ctx.fillRect(x, y + 5, 2, ROW_H - 10);
        }

        ctx.font = '12px "Segoe UI", system-ui, sans-serif';
        ctx.fillStyle = mod.on ? "#e9ecf4" : "#79839a";
        ctx.fillText(mod.name, x + 10, y + ROW_H / 2);

        // Bind chip, only once something is bound or the row is hovered, so a full category is
        // not a wall of empty brackets.
        var keyText = mod.key > 0 ? MS.sandbox.keyName(mod.key) : (hot ? "bind" : "");
        if (keyText) {
            ctx.font = '9px "Segoe UI", system-ui, sans-serif';
            var kw = ctx.measureText(keyText).width + 10;
            var kx = x + w - 44 - kw;
            ctx.fillStyle = "rgba(255,255,255,0.06)";
            roundRect(kx, y + (ROW_H - 13) / 2, kw, 13, 3);
            ctx.fill();
            ctx.fillStyle = mod.key > 0 ? "#9fb3d0" : "#5d6577";
            ctx.textAlign = "center";
            ctx.fillText(keyText, kx + kw / 2, y + ROW_H / 2 + 0.5);
            ctx.textAlign = "left";
            region(kx, y, kw, ROW_H, { type: "bind", mod: mod });
        }

        // Toggle
        var tw = 26, th = 13, tx = x + w - tw - 14, ty = y + (ROW_H - th) / 2;
        ctx.fillStyle = mod.on ? rgba(accent, 1) : "rgba(255,255,255,0.13)";
        roundRect(tx, ty, tw, th, th / 2);
        ctx.fill();
        ctx.fillStyle = mod.on ? "#150610" : "#8d95a8";
        ctx.beginPath();
        ctx.arc(mod.on ? tx + tw - th / 2 : tx + th / 2, ty + th / 2, th / 2 - 2, 0, Math.PI * 2);
        ctx.fill();
        region(tx, y, tw + 14, ROW_H, { type: "toggle", mod: mod });

        if (mod.settings.length) {
            ctx.font = '9px "Segoe UI", system-ui, sans-serif';
            ctx.fillStyle = "#6b7489";
            ctx.fillText(state.expanded[mod.name] ? "▾" : "▸", x + w - 9, y + ROW_H / 2 + 1);
        }
        region(x, y, w - tw - 44, ROW_H, { type: "expand", mod: mod });
    }

    function drawSetting(sandbox, s, x, y, w, accent) {
        ctx.font = '11px "Segoe UI", system-ui, sans-serif';
        var mid = y + SET_H / 2;

        if (s.type === "toggle") {
            ctx.fillStyle = "#98a1b6";
            ctx.fillText(s.label, x, mid);
            var bx = x + w - 12;
            ctx.strokeStyle = s.value ? rgba(accent, 1) : "rgba(255,255,255,0.22)";
            ctx.lineWidth = 1;
            ctx.strokeRect(bx + 0.5, mid - 5.5, 11, 11);
            if (s.value) {
                ctx.fillStyle = rgba(accent, 1);
                ctx.fillRect(bx + 3, mid - 3, 6, 6);
            }
            region(x, y, w, SET_H, { type: "set-toggle", setting: s });
            return;
        }

        if (s.type === "mode") {
            ctx.fillStyle = "#98a1b6";
            ctx.fillText(s.label, x, mid);
            var text = s.options[s.value];
            var tw2 = ctx.measureText(text).width;
            ctx.fillStyle = rgba(accent, 1);
            ctx.textAlign = "right";
            ctx.fillText(text, x + w, mid);
            ctx.textAlign = "left";
            region(x, y, w, SET_H, { type: "set-mode", setting: s });
            return;
        }

        if (s.type === "color") {
            ctx.fillStyle = "#98a1b6";
            ctx.fillText(s.label, x, mid);
            var sw = 34;
            ctx.fillStyle = "rgb(" + s.value[0] + "," + s.value[1] + "," + s.value[2] + ")";
            roundRect(x + w - sw, mid - 6, sw, 12, 3);
            ctx.fill();
            ctx.strokeStyle = "rgba(255,255,255,0.18)";
            ctx.lineWidth = 1;
            ctx.stroke();
            region(x + w - sw, y, sw, SET_H, { type: "set-color", setting: s });
            // The hue strip appears under the swatch while it is the one being edited.
            if (state.editingColor === s) {
                drawHueStrip(s, x, y + SET_H - 2, w);
            }
            return;
        }

        var shown = (Math.round(s.value * 100) / 100) + (s.unit ? " " + s.unit : "");
        ctx.fillStyle = "#98a1b6";
        ctx.fillText(s.label, x, mid - 5);
        ctx.textAlign = "right";
        ctx.fillStyle = "#dfe4ee";
        ctx.fillText(shown, x + w, mid - 5);
        ctx.textAlign = "left";

        var trackY = mid + 5;
        ctx.fillStyle = "rgba(255,255,255,0.12)";
        ctx.fillRect(x, trackY - 1, w, 2);
        var frac = (s.value - s.min) / (s.max - s.min);
        ctx.fillStyle = rgba(accent, 1);
        ctx.fillRect(x, trackY - 1, w * frac, 2);
        ctx.beginPath();
        ctx.arc(x + w * frac, trackY, 4, 0, Math.PI * 2);
        ctx.fill();
        region(x, y, w, SET_H, { type: "set-slider", setting: s, x: x, w: w });
    }

    function drawHueStrip(s, x, y, w) {
        var h = 10;
        for (var i = 0; i < w; i++) {
            ctx.fillStyle = "hsl(" + Math.round(i / w * 360) + ",85%,58%)";
            ctx.fillRect(x + i, y, 1, h);
        }
        region(x, y, w, h, { type: "set-hue", setting: s, x: x, w: w });
        state.hueStrip = { x: x, y: y, w: w, h: h, setting: s };
    }

    /* ----------------------------------------------------------------- input */

    function pick() {
        for (var i = regions.length - 1; i >= 0; i--) {
            var r = regions[i];
            if (state.mouseX >= r.x && state.mouseX <= r.x + r.w
                && state.mouseY >= r.y && state.mouseY <= r.y + r.h) {
                return r.p;
            }
        }
        return null;
    }

    GUI.mouseMove = function (mx, my) {
        state.mouseX = mx;
        state.mouseY = my;
        if (state.drag) {
            if (state.drag.type === "header") {
                state.x = mx - state.drag.dx;
                state.y = my - state.drag.dy;
            } else if (state.drag.type === "slider") {
                applySlider(state.drag.p, mx);
            } else if (state.drag.type === "hue") {
                applyHue(state.drag.p, mx);
            }
        }
    };

    function applySlider(p, mx) {
        var s = p.setting;
        var frac = Math.max(0, Math.min(1, (mx - p.x) / p.w));
        var raw = s.min + frac * (s.max - s.min);
        s.value = Math.max(s.min, Math.min(s.max, Math.round(raw / s.step) * s.step));
        s.value = Math.round(s.value * 1000) / 1000;
    }

    function applyHue(p, mx) {
        var frac = Math.max(0, Math.min(1, (mx - p.x) / p.w));
        p.setting.value = hslToRgb(frac * 360, 0.85, 0.58);
    }

    function hslToRgb(h, s, l) {
        var c = (1 - Math.abs(2 * l - 1)) * s;
        var hp = h / 60;
        var x = c * (1 - Math.abs((hp % 2) - 1));
        var r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        var m = l - c / 2;
        return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
    }

    GUI.mouseDown = function (button) {
        if (!state.open) return false;
        var hit = pick();
        if (!hit) {
            state.searching = false;
            state.editingColor = null;
            return true;
        }
        switch (hit.type) {
            case "header":
                state.drag = { type: "header", dx: state.mouseX - state.x, dy: state.mouseY - state.y };
                break;
            case "cat":
                state.cat = hit.value;
                state.scroll = 0;
                break;
            case "search":
                state.searching = true;
                break;
            case "toggle":
                hit.mod.on = !hit.mod.on;
                MS.sandbox.onToggle(hit.mod);
                break;
            case "expand":
                if (button === 2) { state.binding = hit.mod; }
                else if (hit.mod.settings.length) {
                    state.expanded[hit.mod.name] = !state.expanded[hit.mod.name];
                }
                break;
            case "bind":
                state.binding = hit.mod;
                break;
            case "set-toggle":
                hit.setting.value = !hit.setting.value;
                break;
            case "set-mode":
                var len = hit.setting.options.length;
                hit.setting.value = (hit.setting.value + (button === 2 ? -1 : 1) + len) % len;
                break;
            case "set-color":
                state.editingColor = state.editingColor === hit.setting ? null : hit.setting;
                break;
            case "set-hue":
                state.drag = { type: "hue", p: hit };
                applyHue(hit, state.mouseX);
                break;
            case "set-slider":
                state.drag = { type: "slider", p: hit };
                applySlider(hit, state.mouseX);
                break;
        }
        return true;
    };

    GUI.mouseUp = function () { state.drag = null; };

    GUI.wheel = function (delta) {
        if (!state.open) return false;
        var max = Math.max(0, (state.contentH || 0) - (state.viewH || 0));
        state.scroll = Math.max(0, Math.min(max, state.scroll + delta));
        return true;
    };

    /** Returns true when the GUI consumed the key. */
    GUI.key = function (e) {
        if (!state.open) return false;
        if (state.binding) {
            if (e.code === "Escape") state.binding.key = 0;
            else state.binding.key = e.keyCode || 0;
            state.binding = null;
            return true;
        }
        if (state.searching) {
            if (e.code === "Escape") { state.searching = false; state.search = ""; }
            else if (e.code === "Backspace") state.search = state.search.slice(0, -1);
            else if (e.key && e.key.length === 1) state.search += e.key;
            return true;
        }
        return false;
    };

}(window.MS = window.MS || {}));
