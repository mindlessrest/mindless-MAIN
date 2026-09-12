/*
 * The player model, the held item, and the dummy that stands in the plaza to be hit.
 *
 * Six boxes at Minecraft's own proportions, each with its own pivot, because the walk cycle and
 * the swing are rotations about a joint rather than offsets: an arm that translates instead of
 * swinging is the thing that reads as wrong immediately even when nobody can say why.
 */
(function (MS) {
    "use strict";

    var E = MS.Entity = {};
    var mat = null;

    // One Minecraft pixel. Every measurement below is in them, the way the model actually is,
    // rather than in blocks with the conversion already done and the intent lost.
    var P = 1 / 16;

    function boxMesh(w, h, d, tile, tileTop, tileBottom) {
        var hw = w / 2, hd = d / 2;
        var faces = [
            { v: [[-hw, h, -hd], [-hw, h, hd], [hw, h, hd], [hw, h, -hd]], t: tileTop === undefined ? tile : tileTop, s: 1.0 },
            { v: [[-hw, 0, -hd], [hw, 0, -hd], [hw, 0, hd], [-hw, 0, hd]], t: tileBottom === undefined ? tile : tileBottom, s: 0.55 },
            { v: [[-hw, 0, hd], [hw, 0, hd], [hw, h, hd], [-hw, h, hd]], t: tile, s: 0.88 },
            { v: [[hw, 0, -hd], [-hw, 0, -hd], [-hw, h, -hd], [hw, h, -hd]], t: tile, s: 0.88 },
            { v: [[hw, 0, hd], [hw, 0, -hd], [hw, h, -hd], [hw, h, hd]], t: tile, s: 0.72 },
            { v: [[-hw, 0, -hd], [-hw, 0, hd], [-hw, h, hd], [-hw, h, -hd]], t: tile, s: 0.72 }
        ];
        var data = [];
        for (var f = 0; f < faces.length; f++) {
            var uv = MS.GL.tileUV(faces[f].t);
            var quadUV = [[uv[0], uv[3]], [uv[2], uv[3]], [uv[2], uv[1]], [uv[0], uv[1]]];
            var order = [0, 1, 2, 0, 2, 3];
            for (var i = 0; i < 6; i++) {
                var k = order[i];
                var c = faces[f].v[k];
                data.push(c[0], c[1], c[2], quadUV[k][0], quadUV[k][1], faces[f].s);
            }
        }
        var mesh = MS.GL.createMesh();
        MS.GL.uploadMesh(mesh, new Float32Array(data));
        return mesh;
    }

    var parts = null;

    E.init = function () {
        mat = MS.GL.mat;
        var TILE = MS.GL.TILE;
        parts = {
            head: boxMesh(8 * P, 8 * P, 8 * P, TILE.EYES, TILE.HAIR, TILE.SKIN),
            body: boxMesh(8 * P, 12 * P, 4 * P, TILE.SHIRT),
            arm: boxMesh(4 * P, 12 * P, 4 * P, TILE.SHIRT, TILE.SHIRT, TILE.SKIN),
            leg: boxMesh(4 * P, 12 * P, 4 * P, TILE.PANTS, TILE.PANTS, TILE.SHOE),
            item: boxMesh(1 * P, 12 * P, 6 * P, TILE.SWORD)
        };
        E.parts = parts;
    };

    function compose() {
        var out = mat.identity();
        for (var i = 0; i < arguments.length; i++) {
            out = mat.multiply(new Float32Array(16), out, arguments[i]);
        }
        return out;
    }

    /**
     * Draws a humanoid at a world position.
     *
     * limbSwing drives the walk, swing drives the right arm's chop. Both are passed in rather
     * than tracked here so the same model serves the player, the dummy, and anything else.
     */
    E.drawHumanoid = function (x, y, z, bodyYaw, headYaw, headPitch, limbSwing, limbAmount, swing, tint, held) {
        var base = compose(mat.translation(x, y, z), mat.rotationY(-bodyYaw));

        var swingAngle = Math.cos(limbSwing) * 0.85 * limbAmount;
        var swingAlt = Math.cos(limbSwing + Math.PI) * 0.85 * limbAmount;

        // Legs pivot at the hip, so they are modelled downward from it: the mesh grows upward from
        // its origin, which is why each leg is flipped before being hung under the body.
        var hipY = 12 * P;
        drawPart(parts.leg, compose(base, mat.translation(-2 * P, hipY, 0), mat.rotationX(swingAngle), mat.scaling(1, -1, 1)), tint);
        drawPart(parts.leg, compose(base, mat.translation(2 * P, hipY, 0), mat.rotationX(swingAlt), mat.scaling(1, -1, 1)), tint);

        drawPart(parts.body, compose(base, mat.translation(0, hipY, 0)), tint);

        var shoulderY = 24 * P;
        var armSwing = swing > 0 ? -Math.sin(swing * Math.PI) * 2.1 : swingAlt;
        var armTwist = swing > 0 ? Math.sin(swing * Math.PI) * 0.4 : 0;
        var rightArm = compose(base, mat.translation(-6 * P, shoulderY, 0),
            mat.rotationX(armSwing), mat.rotationZ(armTwist), mat.scaling(1, -1, 1));
        drawPart(parts.arm, rightArm, tint);
        drawPart(parts.arm, compose(base, mat.translation(6 * P, shoulderY, 0),
            mat.rotationX(swingAngle), mat.scaling(1, -1, 1)), tint);

        if (held) {
            drawPart(parts.item, compose(base, mat.translation(-6 * P, shoulderY, 0),
                mat.rotationX(armSwing), mat.rotationZ(armTwist),
                mat.translation(0, -11 * P, -3 * P), mat.rotationX(-0.6)), tint);
        }

        // The head turns relative to the body, and pitches about its own base, which is what
        // keeps it attached while looking straight up.
        drawPart(parts.head, compose(base, mat.translation(0, shoulderY, 0),
            mat.rotationY(-(headYaw - bodyYaw)), mat.rotationX(-headPitch)), tint);
    };

    function drawPart(mesh, model, tint) {
        MS.GL.drawMesh(mesh, model, tint);
    }

    /**
     * The held item in first person.
     *
     * Placed in view space rather than world space: it is parented to the camera, so it is drawn
     * with an identity view and its own small projection, after the world and before the HUD.
     */
    E.drawFirstPersonItem = function (swing, sneaking, bobX, bobY) {
        var progress = swing > 0 ? Math.sin(swing * Math.PI) : 0;
        // Half a metre from the eye, so everything here is small. A sword modelled at world scale
        // and parked this close fills the screen like a mast, which is what the first attempt did.
        var sink = progress * 0.09 + (sneaking ? 0.05 : 0);
        // Screen size here is set by distance as much as by scale, and both were far too generous
        // at first: a blade three quarters of a block tall, parked half a metre from the eye,
        // subtends a third of the view and reads as a mast rather than a sword.
        var k = 0.22;
        MS.GL.drawMesh(parts.item, compose(
            mat.translation(0.30 + bobX * 0.010, -0.21 - sink + bobY * 0.010, -0.58),
            mat.rotationY(-0.55),
            mat.rotationZ(0.45 - progress * 0.55),
            mat.rotationX(-0.25 + progress * 1.0),
            mat.scaling(k, k, k)
        ), null);
        MS.GL.drawMesh(parts.arm, compose(
            mat.translation(0.36 + bobX * 0.010, -0.34 - sink + bobY * 0.010, -0.50),
            mat.rotationZ(-0.40),
            mat.rotationX(-1.30 + progress * 0.85),
            mat.scaling(k, k, k)
        ), null);
    };

}(window.MS = window.MS || {}));
