/*
 * WebGL floor for the sandbox: matrices, a procedural block atlas, and the two programs
 * everything is drawn with.
 *
 * The old sandbox was a grid raycaster, which cannot look up, cannot show the player, and
 * therefore cannot do F5 at all. A real projection is the only way any of that works, and once
 * there is one the rest of the scene stops being a special case: blocks, the player model and the
 * selection box all go through the same pipeline with a different model matrix.
 */
(function (MS) {
    "use strict";

    var GL = MS.GL = {};

    /* ------------------------------------------------------------------ mat4 */

    function identity() {
        return new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
    }

    function multiply(out, a, b) {
        for (var c = 0; c < 4; c++) {
            for (var r = 0; r < 4; r++) {
                var sum = 0;
                for (var k = 0; k < 4; k++) {
                    sum += a[k * 4 + r] * b[c * 4 + k];
                }
                out[c * 4 + r] = sum;
            }
        }
        return out;
    }

    function perspective(fovY, aspect, near, far) {
        var f = 1 / Math.tan(fovY / 2);
        var out = new Float32Array(16);
        out[0] = f / aspect;
        out[5] = f;
        out[10] = (far + near) / (near - far);
        out[11] = -1;
        out[14] = (2 * far * near) / (near - far);
        return out;
    }

    function translation(x, y, z) {
        var m = identity();
        m[12] = x; m[13] = y; m[14] = z;
        return m;
    }

    function rotationX(a) {
        var m = identity(), c = Math.cos(a), s = Math.sin(a);
        m[5] = c; m[6] = s; m[9] = -s; m[10] = c;
        return m;
    }

    function rotationY(a) {
        var m = identity(), c = Math.cos(a), s = Math.sin(a);
        m[0] = c; m[2] = -s; m[8] = s; m[10] = c;
        return m;
    }

    function rotationZ(a) {
        var m = identity(), c = Math.cos(a), s = Math.sin(a);
        m[0] = c; m[1] = s; m[4] = -s; m[5] = c;
        return m;
    }

    function scaling(x, y, z) {
        var m = identity();
        m[0] = x; m[5] = y; m[10] = z;
        return m;
    }

    /**
     * View matrix from a position and a yaw/pitch pair.
     *
     * Built from an explicit basis rather than from composed Euler rotations. Writing the entries
     * out by hand looks tidier and is how this was wrong the first time: the forward vector has to
     * land exactly on eye-space -Z for every yaw, and a sign slipped in the composition puts the
     * whole world behind the camera at some angles and beside it at others.
     */
    function viewMatrix(x, y, z, yaw, pitch) {
        var cp = Math.cos(pitch), sp = Math.sin(pitch);
        var fx = cp * Math.cos(yaw), fy = sp, fz = cp * Math.sin(yaw);

        // right = normalise(forward x worldUp); worldUp is (0,1,0), so it reduces to this.
        var rl = Math.hypot(-fz, fx) || 1;
        var rx = -fz / rl, ry = 0, rz = fx / rl;

        // up = right x forward
        var ux = ry * fz - rz * fy;
        var uy = rz * fx - rx * fz;
        var uz = rx * fy - ry * fx;

        var out = new Float32Array(16);
        out[0] = rx; out[4] = ry; out[8] = rz;
        out[1] = ux; out[5] = uy; out[9] = uz;
        out[2] = -fx; out[6] = -fy; out[10] = -fz;
        out[3] = 0; out[7] = 0; out[11] = 0;
        out[12] = -(rx * x + ry * y + rz * z);
        out[13] = -(ux * x + uy * y + uz * z);
        out[14] = fx * x + fy * y + fz * z;
        out[15] = 1;
        return out;
    }

    GL.mat = {
        identity: identity, multiply: multiply, perspective: perspective,
        translation: translation, rotationX: rotationX, rotationY: rotationY,
        rotationZ: rotationZ, scaling: scaling, view: viewMatrix
    };

    /* ----------------------------------------------------------------- atlas */

    var ATLAS_TILES = 8;
    var TILE_PX = 16;

    // Tile slots. Index is the position in the atlas, left to right then down.
    var TILE = {
        GRASS_TOP: 0, GRASS_SIDE: 1, DIRT: 2, STONE: 3, COBBLE: 4, PLANKS: 5, LOG_SIDE: 6, LOG_TOP: 7,
        SAND: 8, BRICK: 9, WOOL_WHITE: 10, WOOL_RED: 11, WOOL_BLUE: 12, LEAVES: 13, GLASS: 14, BEDROCK: 15,
        SKIN: 16, SHIRT: 17, PANTS: 18, SHOE: 19, HAIR: 20, EYES: 21,
        IRON: 22, GOLD: 23, DIAMOND: 24, EMERALD: 25, SWORD: 26, BLOCKITEM: 27
    };

    function hash2(x, y, seed) {
        var h = x * 374761393 + y * 668265263 + seed * 2147483647;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) >>> 0) / 4294967295;
    }

    function paintTile(c2d, index, painter) {
        var tx = (index % ATLAS_TILES) * TILE_PX;
        var ty = Math.floor(index / ATLAS_TILES) * TILE_PX;
        for (var y = 0; y < TILE_PX; y++) {
            for (var x = 0; x < TILE_PX; x++) {
                var rgb = painter(x, y);
                if (!rgb) continue;
                c2d.fillStyle = "rgba(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + "," + (rgb[3] === undefined ? 1 : rgb[3]) + ")";
                c2d.fillRect(tx + x, ty + y, 1, 1);
            }
        }
    }

    function noisy(base, amount, seed) {
        return function (x, y) {
            var n = (hash2(x, y, seed) - 0.5) * amount;
            return [clamp8(base[0] + n), clamp8(base[1] + n), clamp8(base[2] + n)];
        };
    }

    function clamp8(v) { return v < 0 ? 0 : v > 255 ? 255 : Math.round(v); }

    function buildAtlas() {
        var c = document.createElement("canvas");
        c.width = c.height = ATLAS_TILES * TILE_PX;
        var g = c.getContext("2d");
        g.clearRect(0, 0, c.width, c.height);

        paintTile(g, TILE.GRASS_TOP, noisy([106, 158, 66], 26, 1));
        paintTile(g, TILE.DIRT, noisy([134, 96, 67], 26, 2));
        paintTile(g, TILE.GRASS_SIDE, function (x, y) {
            // The green lip on a grass side is uneven in Minecraft, which is most of what makes
            // a hillside read as grass rather than as a painted band.
            var lip = 3 + Math.floor(hash2(x, 0, 7) * 2.6);
            var n = (hash2(x, y, y < lip ? 1 : 2) - 0.5) * 26;
            return y < lip
                ? [clamp8(106 + n), clamp8(158 + n), clamp8(66 + n)]
                : [clamp8(134 + n), clamp8(96 + n), clamp8(67 + n)];
        });
        paintTile(g, TILE.STONE, noisy([126, 126, 126], 22, 3));
        paintTile(g, TILE.COBBLE, function (x, y) {
            var cell = Math.floor(hash2(Math.floor(x / 4), Math.floor(y / 4), 4) * 60);
            var n = (hash2(x, y, 5) - 0.5) * 18;
            return [clamp8(112 + cell + n), clamp8(112 + cell + n), clamp8(112 + cell + n)];
        });
        paintTile(g, TILE.PLANKS, function (x, y) {
            var row = Math.floor(y / 4);
            var edge = y % 4 === 0;
            var n = (hash2(x, row, 6) - 0.5) * 16;
            var base = edge ? 120 : 160;
            return [clamp8(base + n), clamp8(base * 0.72 + n), clamp8(base * 0.42 + n)];
        });
        paintTile(g, TILE.LOG_SIDE, function (x, y) {
            var edge = x < 2 || x > 13;
            var n = (hash2(x, y, 8) - 0.5) * 18;
            var base = edge ? 88 : 122;
            return [clamp8(base + n), clamp8(base * 0.7 + n), clamp8(base * 0.4 + n)];
        });
        paintTile(g, TILE.LOG_TOP, function (x, y) {
            var d = Math.hypot(x - 7.5, y - 7.5);
            var ring = Math.sin(d * 2.2) * 12;
            return [clamp8(150 + ring), clamp8(116 + ring), clamp8(72 + ring)];
        });
        paintTile(g, TILE.SAND, noisy([218, 206, 160], 18, 9));
        paintTile(g, TILE.BRICK, function (x, y) {
            var row = Math.floor(y / 4);
            var offset = row % 2 === 0 ? 0 : 4;
            var mortar = y % 4 === 0 || (x + offset) % 8 === 0;
            var n = (hash2(x, y, 10) - 0.5) * 14;
            return mortar ? [clamp8(186 + n), clamp8(178 + n), clamp8(170 + n)]
                          : [clamp8(150 + n), clamp8(84 + n), clamp8(66 + n)];
        });
        paintTile(g, TILE.WOOL_WHITE, noisy([226, 229, 231], 12, 11));
        paintTile(g, TILE.WOOL_RED, noisy([165, 52, 52], 16, 12));
        paintTile(g, TILE.WOOL_BLUE, noisy([56, 84, 168], 16, 13));
        paintTile(g, TILE.LEAVES, function (x, y) {
            var h = hash2(x, y, 14);
            if (h < 0.18) return [0, 0, 0, 0];
            var n = (h - 0.5) * 46;
            return [clamp8(66 + n), clamp8(122 + n), clamp8(52 + n)];
        });
        paintTile(g, TILE.GLASS, function (x, y) {
            var border = x === 0 || y === 0 || x === 15 || y === 15;
            if (border) return [214, 230, 236, 0.85];
            if ((x + y) % 7 === 0) return [200, 220, 230, 0.5];
            return [0, 0, 0, 0];
        });
        paintTile(g, TILE.BEDROCK, function (x, y) {
            var v = 40 + Math.floor(hash2(Math.floor(x / 2), Math.floor(y / 2), 15) * 70);
            return [v, v, v];
        });

        paintTile(g, TILE.SKIN, noisy([226, 179, 141], 12, 20));
        paintTile(g, TILE.SHIRT, noisy([62, 112, 176], 14, 21));
        paintTile(g, TILE.PANTS, noisy([68, 72, 122], 12, 22));
        paintTile(g, TILE.SHOE, noisy([88, 76, 68], 10, 23));
        paintTile(g, TILE.HAIR, noisy([70, 48, 34], 12, 24));
        paintTile(g, TILE.EYES, function (x, y) {
            var n = (hash2(x, y, 25) - 0.5) * 10;
            if (y >= 6 && y <= 9 && ((x >= 3 && x <= 5) || (x >= 10 && x <= 12))) {
                return x === 4 || x === 11 ? [40, 60, 140] : [240, 240, 240];
            }
            return [clamp8(226 + n), clamp8(179 + n), clamp8(141 + n)];
        });

        paintTile(g, TILE.IRON, noisy([206, 206, 206], 20, 30));
        paintTile(g, TILE.GOLD, noisy([236, 196, 78], 20, 31));
        paintTile(g, TILE.DIAMOND, noisy([96, 212, 216], 20, 32));
        paintTile(g, TILE.EMERALD, noisy([70, 190, 100], 20, 33));
        paintTile(g, TILE.SWORD, function (x, y) {
            var onBlade = Math.abs((15 - y) - x) < 2 && y < 13;
            var onGuard = y >= 11 && y <= 12 && x >= 2 && x <= 6;
            var onGrip = y >= 12 && Math.abs((15 - y) - x) < 2;
            if (onBlade) return [214, 220, 228];
            if (onGuard) return [120, 96, 60];
            if (onGrip) return [96, 74, 48];
            return [0, 0, 0, 0];
        });

        GL.atlasCanvas = c;
        return c;
    }

    GL.TILE = TILE;

    GL.tileUV = function (index) {
        var s = 1 / ATLAS_TILES;
        var u = (index % ATLAS_TILES) * s;
        var v = Math.floor(index / ATLAS_TILES) * s;
        // Half a texel in from every edge. Without it, a linear-filtered edge samples the
        // neighbouring tile and every block gets a one pixel fringe of the wrong material.
        var e = 0.5 / (ATLAS_TILES * TILE_PX);
        return [u + e, v + e, u + s - e, v + s - e];
    };

    /* --------------------------------------------------------------- programs */

    function compile(gl, type, source) {
        var sh = gl.createShader(type);
        gl.shaderSource(sh, source);
        gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
            throw new Error("shader: " + gl.getShaderInfoLog(sh));
        }
        return sh;
    }

    function link(gl, vsSource, fsSource) {
        var p = gl.createProgram();
        gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, vsSource));
        gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, fsSource));
        gl.linkProgram(p);
        if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
            throw new Error("link: " + gl.getProgramInfoLog(p));
        }
        return p;
    }

    var BLOCK_VS =
        "attribute vec3 aPos;attribute vec2 aUV;attribute float aShade;" +
        "uniform mat4 uProj,uView,uModel;" +
        "varying vec2 vUV;varying float vShade;varying float vDist;" +
        "void main(){vec4 w=uModel*vec4(aPos,1.0);vec4 e=uView*w;" +
        "vUV=aUV;vShade=aShade;vDist=length(e.xyz);gl_Position=uProj*e;}";

    var BLOCK_FS =
        "precision mediump float;" +
        "uniform sampler2D uTex;uniform vec3 uFog;uniform float uNear,uFar;uniform float uBright;" +
        "uniform vec4 uTint;" +
        "varying vec2 vUV;varying float vShade;varying float vDist;" +
        "void main(){vec4 c=texture2D(uTex,vUV);if(c.a<0.35)discard;" +
        "float f=clamp((vDist-uNear)/(uFar-uNear),0.0,1.0);" +
        "vec3 lit=c.rgb*mix(vShade,1.0,uBright);" +
        "lit=mix(lit,uTint.rgb,uTint.a);" +
        "gl_FragColor=vec4(mix(lit,uFog,f),c.a);}";

    // Untextured, per-vertex colour, for the effect geometry. The block program cannot serve it:
    // everything there is modulated by an atlas sample, and a ring that fades to nothing needs the
    // alpha to come from the vertex rather than from a texel.
    var FLAT_VS =
        "attribute vec3 aPos;attribute vec4 aColor;" +
        "uniform mat4 uProj,uView,uModel;varying vec4 vColor;" +
        "void main(){vColor=aColor;gl_Position=uProj*uView*uModel*vec4(aPos,1.0);}";

    var FLAT_FS =
        "precision mediump float;varying vec4 vColor;" +
        "void main(){gl_FragColor=vColor;}";

    var LINE_VS =
        "attribute vec3 aPos;uniform mat4 uProj,uView,uModel;" +
        "void main(){gl_Position=uProj*uView*uModel*vec4(aPos,1.0);}";

    var LINE_FS =
        "precision mediump float;uniform vec4 uColor;" +
        "void main(){gl_FragColor=uColor;}";

    var gl = null;
    var blockProgram, lineProgram, flatProgram, texture;
    var loc = {};

    GL.init = function (canvas) {
        gl = canvas.getContext("webgl", { antialias: false, alpha: false, depth: true })
            || canvas.getContext("experimental-webgl");
        if (!gl) {
            return null;
        }
        GL.ctx = gl;

        blockProgram = link(gl, BLOCK_VS, BLOCK_FS);
        lineProgram = link(gl, LINE_VS, LINE_FS);
        flatProgram = link(gl, FLAT_VS, FLAT_FS);

        loc.block = {
            aPos: gl.getAttribLocation(blockProgram, "aPos"),
            aUV: gl.getAttribLocation(blockProgram, "aUV"),
            aShade: gl.getAttribLocation(blockProgram, "aShade"),
            uProj: gl.getUniformLocation(blockProgram, "uProj"),
            uView: gl.getUniformLocation(blockProgram, "uView"),
            uModel: gl.getUniformLocation(blockProgram, "uModel"),
            uTex: gl.getUniformLocation(blockProgram, "uTex"),
            uFog: gl.getUniformLocation(blockProgram, "uFog"),
            uNear: gl.getUniformLocation(blockProgram, "uNear"),
            uFar: gl.getUniformLocation(blockProgram, "uFar"),
            uBright: gl.getUniformLocation(blockProgram, "uBright"),
            uTint: gl.getUniformLocation(blockProgram, "uTint")
        };
        loc.line = {
            aPos: gl.getAttribLocation(lineProgram, "aPos"),
            uProj: gl.getUniformLocation(lineProgram, "uProj"),
            uView: gl.getUniformLocation(lineProgram, "uView"),
            uModel: gl.getUniformLocation(lineProgram, "uModel"),
            uColor: gl.getUniformLocation(lineProgram, "uColor")
        };

        loc.flat = {
            aPos: gl.getAttribLocation(flatProgram, "aPos"),
            aColor: gl.getAttribLocation(flatProgram, "aColor"),
            uProj: gl.getUniformLocation(flatProgram, "uProj"),
            uView: gl.getUniformLocation(flatProgram, "uView"),
            uModel: gl.getUniformLocation(flatProgram, "uModel")
        };

        var atlas = buildAtlas();
        texture = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, atlas);
        // Nearest, because a blurred block texture is the single most obvious way a thing stops
        // looking like Minecraft.
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

        gl.enable(gl.DEPTH_TEST);
        gl.enable(gl.CULL_FACE);
        gl.cullFace(gl.BACK);
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
        return gl;
    };

    /* ----------------------------------------------------------------- meshes */

    GL.createMesh = function () {
        return { buffer: gl.createBuffer(), count: 0 };
    };

    /** data is interleaved x,y,z,u,v,shade. */
    GL.uploadMesh = function (mesh, data) {
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.buffer);
        gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
        mesh.count = data.length / 6;
    };

    GL.createLineMesh = function (data) {
        var mesh = { buffer: gl.createBuffer(), count: data.length / 3 };
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.buffer);
        gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
        return mesh;
    };

    var proj = null, view = null, fog = [0.62, 0.75, 0.92];

    GL.beginFrame = function (width, height, projection, viewM, fogColor, brightness) {
        gl.viewport(0, 0, width, height);
        gl.clearColor(fogColor[0], fogColor[1], fogColor[2], 1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        setCamera(projection, viewM, fogColor, brightness);
    };

    /**
     * Swap the camera without clearing.
     *
     * The held item is parented to the camera and therefore needs its own near projection, drawn
     * over a frame that already has the world in it. Reusing beginFrame for that wiped the colour
     * buffer and left nothing on screen but the hand.
     */
    GL.setCamera = function (projection, viewM, fogColor, brightness) {
        setCamera(projection, viewM, fogColor, brightness);
    };

    function setCamera(projection, viewM, fogColor, brightness) {
        proj = projection;
        view = viewM;
        fog = fogColor;

        gl.useProgram(blockProgram);
        gl.uniformMatrix4fv(loc.block.uProj, false, proj);
        gl.uniformMatrix4fv(loc.block.uView, false, view);
        gl.uniform3fv(loc.block.uFog, fog);
        gl.uniform1f(loc.block.uNear, 48);
        gl.uniform1f(loc.block.uFar, 118);
        gl.uniform1f(loc.block.uBright, brightness);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.uniform1i(loc.block.uTex, 0);
    }

    var IDENTITY = identity();
    var NO_TINT = new Float32Array([0, 0, 0, 0]);

    GL.drawMesh = function (mesh, model, tint) {
        if (!mesh.count) {
            return;
        }
        gl.useProgram(blockProgram);
        gl.uniformMatrix4fv(loc.block.uProj, false, proj);
        gl.uniformMatrix4fv(loc.block.uView, false, view);
        gl.uniformMatrix4fv(loc.block.uModel, false, model || IDENTITY);
        gl.uniform4fv(loc.block.uTint, tint || NO_TINT);
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.buffer);
        var stride = 6 * 4;
        gl.enableVertexAttribArray(loc.block.aPos);
        gl.vertexAttribPointer(loc.block.aPos, 3, gl.FLOAT, false, stride, 0);
        gl.enableVertexAttribArray(loc.block.aUV);
        gl.vertexAttribPointer(loc.block.aUV, 2, gl.FLOAT, false, stride, 12);
        gl.enableVertexAttribArray(loc.block.aShade);
        gl.vertexAttribPointer(loc.block.aShade, 1, gl.FLOAT, false, stride, 20);
        gl.drawArrays(gl.TRIANGLES, 0, mesh.count);
    };

    GL.drawLines = function (mesh, model, color, width) {
        if (!mesh.count) {
            return;
        }
        gl.useProgram(lineProgram);
        gl.uniformMatrix4fv(loc.line.uProj, false, proj);
        gl.uniformMatrix4fv(loc.line.uView, false, view);
        gl.uniformMatrix4fv(loc.line.uModel, false, model || IDENTITY);
        gl.uniform4fv(loc.line.uColor, color);
        gl.lineWidth(width || 1);
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.buffer);
        gl.enableVertexAttribArray(loc.line.aPos);
        gl.vertexAttribPointer(loc.line.aPos, 3, gl.FLOAT, false, 0, 0);
        gl.drawArrays(gl.LINES, 0, mesh.count);
    };

    /** A buffer rewritten every frame, for geometry that only exists for one. */
    GL.createDynamicMesh = function () {
        return { buffer: gl.createBuffer(), count: 0, dynamic: true };
    };

    /** data is interleaved x,y,z,r,g,b,a with colour in 0..1. */
    GL.uploadFlat = function (mesh, data) {
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.buffer);
        gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
        mesh.count = data.length / 7;
    };

    GL.drawFlat = function (mesh) {
        if (!mesh.count) {
            return;
        }
        gl.useProgram(flatProgram);
        gl.uniformMatrix4fv(loc.flat.uProj, false, proj);
        gl.uniformMatrix4fv(loc.flat.uView, false, view);
        gl.uniformMatrix4fv(loc.flat.uModel, false, IDENTITY);
        gl.bindBuffer(gl.ARRAY_BUFFER, mesh.buffer);
        var stride = 7 * 4;
        gl.enableVertexAttribArray(loc.flat.aPos);
        gl.vertexAttribPointer(loc.flat.aPos, 3, gl.FLOAT, false, stride, 0);
        gl.enableVertexAttribArray(loc.flat.aColor);
        gl.vertexAttribPointer(loc.flat.aColor, 4, gl.FLOAT, false, stride, 12);
        // Effects never write depth: they are translucent, and two of them overlapping would
        // otherwise punch a hole in each other depending on draw order.
        gl.depthMask(false);
        gl.drawArrays(gl.TRIANGLES, 0, mesh.count);
        gl.depthMask(true);
        gl.disableVertexAttribArray(loc.flat.aColor);
    };

    GL.depthTest = function (on) {
        if (on) gl.enable(gl.DEPTH_TEST); else gl.disable(gl.DEPTH_TEST);
    };

    GL.cull = function (on) {
        if (on) gl.enable(gl.CULL_FACE); else gl.disable(gl.CULL_FACE);
    };

}(window.MS = window.MS || {}));
