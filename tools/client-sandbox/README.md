# Mindless client sandbox

Run `client-sandbox.bat` from the repository root, then leave the PowerShell window open.

Standing in a world with the client's HUD over it. The parts that are tuned by eye rather than
reasoned about live here: the array list and its connected background, the click GUI, the bind
list, and the effect presets.

Controls: `WASD` move, `Space` jump, `Left Shift` sneak, `Left Ctrl` sprint, left click swings,
`Right Shift` opens and closes the click GUI, `Esc` releases the mouse. In the GUI, click a row
to toggle it, the arrow to open its settings, and drag the header to move the panel.

The array list background is transcribed from `HUD.paintBackgroundShapes` rather than
approximated, including the inverse fillets at width changes, so a radius settled here is the
radius to put in the slider. The effects use the same easing and the same lifetimes as
`mindless/effect`.

The world is a grid raycaster and is not trying to be Minecraft. It is there so a translucent
panel is judged over moving terrain instead of over a flat colour, which is the only way that
judgement means anything. Final validation still belongs in the game, which owns the real
depth buffer, lighting and font.
