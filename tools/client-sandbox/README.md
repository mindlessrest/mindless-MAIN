# Mindless client sandbox

Run `client-sandbox.bat` from the repository root, then leave the PowerShell window open.

A playable stand-in for the game with the client's HUD and click GUI over it. The parts that are
judged by eye rather than reasoned about live here: the array list and its connected background,
the click GUI and its theme, the bind list, the keystrokes, and the effect presets.

## Controls

| | |
|---|---|
| Move, jump, sneak, sprint | `WASD`, `Space`, `Left Shift`, `Left Ctrl` |
| Break or attack | left click |
| Place | right click |
| Hotbar | `1`–`9`, mouse wheel |
| Perspective | `F5` cycles first person, third back, third front |
| Debug overlay | `F3` |
| Hide the HUD | `F1` |
| Click GUI | `Right Shift` |
| Release the mouse | `Esc` |

In the GUI: click a row to expand it, the pill to toggle it, right click a row or click its bind
chip to rebind it, drag the header to move the panel, scroll the list, and type in the search box
to search every category at once. `Client` → `Click GUI` themes the panel itself: accent, opacity,
rounding, scale, how far the world behind it is dimmed.

## What is real and what is a stand-in

Real, in the sense of being the same code path the client takes:

- The array list background is transcribed from `HUD.paintBackgroundShapes`, inverse fillets at
  width changes included. A corner radius or a step rounding settled here is the number to put in
  the slider.
- The hit and jump effects use the lifetimes and the easing in `mindless/effect`.
- The bind list behaves the way `BindGUI` does, including saying so when nothing is bound.

A stand-in: the world, the player model, the block textures, and the item rendering. The world is
a voxel grid with a real perspective camera, which is what `F5` and looking straight up both need,
but it is not Minecraft and is not trying to be. It is there so the HUD is judged over terrain,
motion and varying brightness instead of over a flat colour, which is the only way that judgement
carries. Final validation still belongs in the game, which owns the real depth buffer, lighting
and font.

## Files

| | |
|---|---|
| `gl.js` | matrices, the procedural block atlas, and the three shader programs |
| `world.js` | voxel storage, terrain, meshing, raycast, collision |
| `entity.js` | the humanoid model and the first person hand |
| `hud.js` | Minecraft's HUD, and the client's |
| `gui.js` | the click GUI |
| `sandbox.js` | module list, input, effects, and the frame loop |
