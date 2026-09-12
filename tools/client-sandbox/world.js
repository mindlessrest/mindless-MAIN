/*
 * The voxel world, its mesh, and the collision the player is resolved against.
 *
 * Small enough that the whole thing is one mesh rebuilt on demand rather than chunked. A rebuild
 * costs a few milliseconds and only happens when a block changes, which is the one moment a frame
 * can afford to be late; chunking would buy nothing here and adds a boundary to get wrong.
 */
(function (MS) {
    "use strict";

    var World = MS.World = {};
    var T = null;

    var SX = 96, SY = 40, SZ = 96;
    World.SX = SX; World.SY = SY; World.SZ = SZ;

    var blocks = new Uint8Array(SX * SY * SZ);

    // id: name, tiles as [top, side, bottom], solid, and whether it hides the face next to it.
    var TYPES = [];

    function defineTypes() {
        var TILE = MS.GL.TILE;
        TYPES = [
            null,
            { name: "Grass Block", t: [TILE.GRASS_TOP, TILE.GRASS_SIDE, TILE.DIRT], solid: true, opaque: true },
            { name: "Dirt", t: [TILE.DIRT, TILE.DIRT, TILE.DIRT], solid: true, opaque: true },
            { name: "Stone", t: [TILE.STONE, TILE.STONE, TILE.STONE], solid: true, opaque: true },
            { name: "Cobblestone", t: [TILE.COBBLE, TILE.COBBLE, TILE.COBBLE], solid: true, opaque: true },
            { name: "Oak Planks", t: [TILE.PLANKS, TILE.PLANKS, TILE.PLANKS], solid: true, opaque: true },
            { name: "Oak Log", t: [TILE.LOG_TOP, TILE.LOG_SIDE, TILE.LOG_TOP], solid: true, opaque: true },
            { name: "Sand", t: [TILE.SAND, TILE.SAND, TILE.SAND], solid: true, opaque: true },
            { name: "Bricks", t: [TILE.BRICK, TILE.BRICK, TILE.BRICK], solid: true, opaque: true },
            { name: "White Wool", t: [TILE.WOOL_WHITE, TILE.WOOL_WHITE, TILE.WOOL_WHITE], solid: true, opaque: true },
            { name: "Red Wool", t: [TILE.WOOL_RED, TILE.WOOL_RED, TILE.WOOL_RED], solid: true, opaque: true },
            { name: "Blue Wool", t: [TILE.WOOL_BLUE, TILE.WOOL_BLUE, TILE.WOOL_BLUE], solid: true, opaque: true },
            { name: "Oak Leaves", t: [TILE.LEAVES, TILE.LEAVES, TILE.LEAVES], solid: true, opaque: false },
            { name: "Glass", t: [TILE.GLASS, TILE.GLASS, TILE.GLASS], solid: true, opaque: false },
            { name: "Bedrock", t: [TILE.BEDROCK, TILE.BEDROCK, TILE.BEDROCK], solid: true, opaque: true }
        ];
        World.TYPES = TYPES;
    }

    function index(x, y, z) { return (y * SZ + z) * SX + x; }

    World.inside = function (x, y, z) {
        return x >= 0 && y >= 0 && z >= 0 && x < SX && y < SY && z < SZ;
    };

    World.get = function (x, y, z) {
        if (!World.inside(x, y, z)) return 0;
        return blocks[index(x, y, z)];
    };

    World.set = function (x, y, z, id) {
        if (!World.inside(x, y, z)) return false;
        if (blocks[index(x, y, z)] === id) return false;
        blocks[index(x, y, z)] = id;
        World.dirty = true;
        return true;
    };

    World.solid = function (x, y, z) {
        var id = World.get(x, y, z);
        return id !== 0 && TYPES[id] && TYPES[id].solid;
    };

    /* -------------------------------------------------------------- generate */

    function noise2(x, z) {
        // Three octaves of value noise, smoothed. Enough relief that the horizon is not a ruler
        // and hills actually occlude each other, which is what sells depth.
        var total = 0, amp = 1, freq = 0.045, norm = 0;
        for (var o = 0; o < 3; o++) {
            total += smooth(x * freq, z * freq, o) * amp;
            norm += amp;
            amp *= 0.5;
            freq *= 2.1;
        }
        return total / norm;
    }

    function valueAt(xi, zi, seed) {
        var h = xi * 374761393 + zi * 668265263 + seed * 144665;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) >>> 0) / 4294967295;
    }

    function smooth(x, z, seed) {
        var x0 = Math.floor(x), z0 = Math.floor(z);
        var fx = x - x0, fz = z - z0;
        var sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        var a = valueAt(x0, z0, seed), b = valueAt(x0 + 1, z0, seed);
        var c = valueAt(x0, z0 + 1, seed), d = valueAt(x0 + 1, z0 + 1, seed);
        return (a * (1 - sx) + b * sx) * (1 - sz) + (c * (1 - sx) + d * sx) * sz;
    }

    function tree(x, y, z) {
        var h = 4 + Math.floor(valueAt(x, z, 91) * 3);
        for (var i = 0; i < h; i++) World.set(x, y + i, z, 6);
        for (var dy = -2; dy <= 1; dy++) {
            var r = dy <= -1 ? 2 : 1;
            for (var dx = -r; dx <= r; dx++) {
                for (var dz = -r; dz <= r; dz++) {
                    if (dx === 0 && dz === 0 && dy < 1) continue;
                    if (Math.abs(dx) === r && Math.abs(dz) === r && valueAt(x + dx, z + dz, 3) < 0.4) continue;
                    if (World.get(x + dx, y + h + dy, z + dz) === 0) {
                        World.set(x + dx, y + h + dy, z + dz, 12);
                    }
                }
            }
        }
    }

    World.generate = function () {
        defineTypes();
        blocks.fill(0);
        var x, y, z;
        World.heights = new Uint8Array(SX * SZ);

        for (z = 0; z < SZ; z++) {
            for (x = 0; x < SX; x++) {
                var h = Math.floor(10 + noise2(x, z) * 9);
                World.heights[z * SX + x] = h;
                for (y = 0; y <= h; y++) {
                    var id = y === 0 ? 14 : y === h ? 1 : (y > h - 4 ? 2 : 3);
                    blocks[index(x, y, z)] = id;
                }
            }
        }

        // A plaza to stand in, so the HUD is judged against a flat ground as well as a lumpy one.
        for (z = 44; z < 58; z++) {
            for (x = 40; x < 56; x++) {
                var ph = 14;
                for (y = 0; y < SY; y++) {
                    blocks[index(x, y, z)] = y < ph ? (y === 0 ? 14 : 3) : 0;
                }
                blocks[index(x, ph - 1, z)] = (x + z) % 2 === 0 ? 5 : 8;
                World.heights[z * SX + x] = ph - 1;
            }
        }

        // A hut, a wool wall in three colours and some trees: different brightnesses behind the
        // array list, which is the point of having anything here at all.
        buildHut(60, 15, 46);
        for (z = 0; z < 6; z++) {
            for (y = 0; y < 4; y++) {
                World.set(34, 15 + y, 46 + z, y % 2 === 0 ? 9 : (z % 2 === 0 ? 10 : 11));
            }
        }
        for (var t = 0; t < 26; t++) {
            var tx = 6 + Math.floor(valueAt(t, 1, 55) * (SX - 12));
            var tz = 6 + Math.floor(valueAt(t, 2, 56) * (SZ - 12));
            if (tx > 36 && tx < 60 && tz > 40 && tz < 62) continue;
            tree(tx, World.heights[tz * SX + tx] + 1, tz);
        }

        World.dirty = true;
    };

    function buildHut(ox, oy, oz) {
        var w = 9, d = 7, h = 5;
        for (var x = 0; x < w; x++) {
            for (var z = 0; z < d; z++) {
                for (var y = 0; y < h; y++) {
                    var wall = x === 0 || z === 0 || x === w - 1 || z === d - 1;
                    var floor = y === 0, roof = y === h - 1;
                    var id = 0;
                    if (floor) id = 5;
                    else if (roof) id = 8;
                    else if (wall) id = (y === 2 && (x === 4 || z === 3)) ? 13 : 4;
                    if (id) World.set(ox + x, oy + y, oz + z, id);
                }
            }
        }
        World.set(ox + 4, oy + 1, oz, 0);
        World.set(ox + 4, oy + 2, oz, 0);
    }

    /* ------------------------------------------------------------------ mesh */

    var FACES = [
        // dir, corners (ccw seen from outside), tile slot, shade
        { n: [0, 1, 0], v: [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]], slot: 0, shade: 1.00 },
        { n: [0, -1, 0], v: [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]], slot: 2, shade: 0.52 },
        { n: [0, 0, 1], v: [[0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]], slot: 1, shade: 0.86 },
        { n: [0, 0, -1], v: [[1, 0, 0], [0, 0, 0], [0, 1, 0], [1, 1, 0]], slot: 1, shade: 0.86 },
        { n: [1, 0, 0], v: [[1, 0, 1], [1, 0, 0], [1, 1, 0], [1, 1, 1]], slot: 1, shade: 0.70 },
        { n: [-1, 0, 0], v: [[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]], slot: 1, shade: 0.70 }
    ];

    World.buildMesh = function (mesh) {
        var data = [];
        var x, y, z, f;
        for (y = 0; y < SY; y++) {
            for (z = 0; z < SZ; z++) {
                for (x = 0; x < SX; x++) {
                    var id = blocks[index(x, y, z)];
                    if (!id) continue;
                    var type = TYPES[id];
                    for (f = 0; f < 6; f++) {
                        var face = FACES[f];
                        var nx = x + face.n[0], ny = y + face.n[1], nz = z + face.n[2];
                        var neighbour = World.get(nx, ny, nz);
                        // A face is drawn when whatever is next to it cannot hide it. Two panes of
                        // glass still hide each other, which is what stops a window stacking into
                        // a solid-looking slab of overdraw.
                        if (neighbour) {
                            var nt = TYPES[neighbour];
                            if (nt && (nt.opaque || neighbour === id)) continue;
                        }
                        emitFace(data, x, y, z, face, type);
                    }
                }
            }
        }
        MS.GL.uploadMesh(mesh, new Float32Array(data));
        World.dirty = false;
        World.triangles = data.length / 18;
    };

    function emitFace(data, x, y, z, face, type) {
        var uv = MS.GL.tileUV(type.t[face.slot]);
        var c = face.v;
        var quadUV = [[uv[0], uv[3]], [uv[2], uv[3]], [uv[2], uv[1]], [uv[0], uv[1]]];
        var order = [0, 1, 2, 0, 2, 3];
        for (var i = 0; i < 6; i++) {
            var k = order[i];
            data.push(x + c[k][0], y + c[k][1], z + c[k][2],
                quadUV[k][0], quadUV[k][1], face.shade);
        }
    }

    /* --------------------------------------------------------------- raycast */

    /** Voxel DDA. Returns the hit block and the face it was entered through. */
    World.raycast = function (ox, oy, oz, dx, dy, dz, distance) {
        var x = Math.floor(ox), y = Math.floor(oy), z = Math.floor(oz);
        var stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        var tdx = Math.abs(1 / (dx || 1e-9)), tdy = Math.abs(1 / (dy || 1e-9)), tdz = Math.abs(1 / (dz || 1e-9));
        var tx = ((dx > 0 ? x + 1 - ox : ox - x)) * tdx;
        var ty = ((dy > 0 ? y + 1 - oy : oy - y)) * tdy;
        var tz = ((dz > 0 ? z + 1 - oz : oz - z)) * tdz;
        var nx = 0, ny = 0, nz = 0, travelled = 0;

        for (var guard = 0; guard < 256 && travelled <= distance; guard++) {
            if (World.solid(x, y, z)) {
                return { x: x, y: y, z: z, nx: nx, ny: ny, nz: nz, dist: travelled };
            }
            if (tx < ty && tx < tz) { x += stepX; travelled = tx; tx += tdx; nx = -stepX; ny = 0; nz = 0; }
            else if (ty < tz) { y += stepY; travelled = ty; ty += tdy; nx = 0; ny = -stepY; nz = 0; }
            else { z += stepZ; travelled = tz; tz += tdz; nx = 0; ny = 0; nz = -stepZ; }
        }
        return null;
    };

    /* ------------------------------------------------------------- collision */

    var HALF = 0.3, HEIGHT = 1.8;
    World.PLAYER_HALF = HALF;
    World.PLAYER_HEIGHT = HEIGHT;

    function blocked(x, y, z) {
        var x0 = Math.floor(x - HALF), x1 = Math.floor(x + HALF);
        var z0 = Math.floor(z - HALF), z1 = Math.floor(z + HALF);
        var y0 = Math.floor(y + 0.001), y1 = Math.floor(y + HEIGHT - 0.001);
        for (var yy = y0; yy <= y1; yy++) {
            for (var zz = z0; zz <= z1; zz++) {
                for (var xx = x0; xx <= x1; xx++) {
                    if (World.solid(xx, yy, zz)) return true;
                }
            }
        }
        return false;
    }

    World.blocked = blocked;

    /**
     * Moves one axis at a time so a wall stops only the component that runs into it.
     *
     * Resolving all three together is what makes a player stick on an inside corner: the whole
     * move is rejected because one axis of it was bad.
     */
    World.move = function (p, dx, dy, dz, stepUp) {
        var onGround = false;

        if (dx) {
            if (!blocked(p.x + dx, p.y, p.z)) p.x += dx;
            else if (stepUp && !blocked(p.x + dx, p.y + 0.55, p.z) && p.onGround) { p.y += 0.55; p.x += dx; }
        }
        if (dz) {
            if (!blocked(p.x, p.y, p.z + dz)) p.z += dz;
            else if (stepUp && !blocked(p.x, p.y + 0.55, p.z + dz) && p.onGround) { p.y += 0.55; p.z += dz; }
        }
        if (dy) {
            if (!blocked(p.x, p.y + dy, p.z)) {
                p.y += dy;
            } else {
                if (dy < 0) {
                    // Land exactly on the surface rather than a fraction inside it, or the next
                    // ground check sees the player embedded and the camera sinks a pixel a frame.
                    p.y = Math.floor(p.y + dy) + 1;
                    onGround = true;
                }
                p.vy = 0;
            }
        }
        if (!onGround) {
            onGround = blocked(p.x, p.y - 0.02, p.z);
        }
        return onGround;
    };

}(window.MS = window.MS || {}));
