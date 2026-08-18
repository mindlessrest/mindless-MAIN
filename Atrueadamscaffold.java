String CLUTCH = "Clutch";
String[] BL = {"ladder", "web", "vine", "chest"};
long airT;
boolean air, rmb;
int saved = -1, ice = -1, iceBack;
String jm;
List placed = new ArrayList();
mindless.utility.font.RavenFontRenderer fr;
String fName = "";

void onLoad() {
    modules.registerDescription("made by !poor ~ ty grand for base");
    modules.registerButton("Debug", false);
    modules.registerButton("Tower", true);
    modules.registerButton("Stop on Shift", true);
    modules.registerButton("Autojump", true);
    modules.registerButton("Block Counter", true);
    modules.registerButton("Show Speed", true);
    modules.registerButton("Block Highlight", false);
    modules.registerButton("Silent Switch", true);
    modules.registerButton("Ice Boost", true);
    modules.registerColor("Highlight Color", 100, 255, 100, 100);
    modules.registerSlider("Delay", "ms", 100, 0, 150, 10);
    modules.registerSlider("Diagonal Delay", "ms", 70, 0, 100, 10);
    modules.registerSlider("Ice Fall Time", "ms", 380, 50, 1000, 10);
    modules.registerSlider("Reach", " blocks", 4.5, 1, 4.5, 0.5);
    modules.registerSlider("Speed", "", 100, 1, 100, 50);
    modules.registerSlider("Snapback Speed", "", 50, 1, 50, 25);
    modules.registerSlider("Max distance", " blocks", 10, 1, 20, 5);
    modules.registerSlider("Rotation Tolerance", "", 25, 0, 90, 5);
    modules.registerButton("Simulate future position", true);
    modules.registerSlider("Minimum fall distance", " blocks", 0, 0, 1, 0);
    try {
        String[] o = mindless.utility.font.FontManager.getHudFontOptions();
        int d = 0;
        for (int i = 0; i < o.length; i++) if ("Sf-Ui".equals(o[i])) d = i;
        modules.registerSlider("Font", d, o);
    } catch (Exception e) {}
}

void onEnable() { push(); clearBind(); jump(btn("Autojump")); }

void onDisable() {
    saved = -1; ice = -1;
    clutch(false); jump(false);
    air = false; rmb = false;
    placed.clear();
}

boolean onMouse(int b, boolean s, int x, int y) { if (b == 1) rmb = s; return true; }

boolean onPacketSent(CPacket p) {
    try {
        C08 c = (C08) p;
        if (c.position != null && c.itemStack != null && c.itemStack.isBlock)
            placed.add(new Object[]{placedPos(c), Long.valueOf(client.time())});
    } catch (Exception e) {}
    return true;
}

void onPreUpdate() {
    Entity p = client.getPlayer();
    boolean fall = p != null && !p.onGround(), silent = btn("Silent Switch"), on = modules.isEnabled(CLUTCH);
    if (silent && ice == -1) {
        if (!on && !fall) {
            int c = slot();
            if (blHeld(c)) { int s = safe(); if (s > -1) { set(s); c = s; } }
            saved = c;
        }
        if (bad(slot())) { int s = safe(); if (s > -1) set(s); }
    } else if (!silent) saved = -1;
    if (stopped()) { air = false; clutch(false); jump(false); return; }
    jump(btn("Autojump"));
    push();
    if (p == null) return;
    if (fall && !inBL(p)) {
        if (!air) { air = true; airT = client.time(); }
        if (client.time() - airT >= delay()) clutch(true);
    } else { air = false; clutch(false); }
    if (btn("Ice Boost") && p != null) {
        boolean t = air && client.time() - airT >= sld("Ice Fall Time");
        if (t && ice == -1) { int s = findIce(); if (s > -1) { ice = s; iceBack = slot(); set(s); } }
        else if (!t && ice != -1) { set(iceBack); ice = -1; }
    } else if (ice != -1) { set(iceBack); ice = -1; }
}

void onPostMotion() { if (btn("Silent Switch")) revert(); }

void onRenderWorld(float pt) {
    if (btn("Silent Switch")) revert();
    if (!btn("Block Highlight")) { placed.clear(); return; }
    if (placed.isEmpty()) return;
    long now = client.time();
    List dead = new ArrayList();
    int col = color(), a0 = (col >>> 24) & 0xFF;
    if (a0 <= 0) a0 = 255;
    int r = (col >> 16) & 0xFF, g = (col >> 8) & 0xFF, b = col & 0xFF;
    Vec3 cam = render.getPosition();
    gl.push(); gl.depth(false); gl.blend(true); gl.texture2d(false); gl.lighting(false); gl.cull(false); gl.lineSmooth(true);
    for (int i = 0; i < placed.size(); i++) {
        Object[] d = (Object[]) placed.get(i);
        long e = now - ((Long) d[1]).longValue();
        if (e > 1350) { dead.add(d); continue; }
        float m = e < 150 ? smooth(e / 150f) : e > 850 ? 1 - smooth(Math.min(1, (e - 850) / 500f)) : 1;
        box((Vec3) d[0], cam, r, g, b, (int) (a0 * m));
    }
    gl.depth(true); gl.texture2d(true); gl.blend(false); gl.cull(true); gl.lineSmooth(false); gl.pop();
    placed.removeAll(dead);
}

void onRenderTick(float pt) {
    if (btn("Silent Switch")) revert();
    int[] d = client.getDisplaySize();
    font();
    if (btn("Block Counter")) {
        int n = 0;
        ItemStack ic = null, h = inv(slot());
        if (h != null && h.isBlock) ic = h;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack s = inv(i);
            if (s != null && s.isBlock) { n += s.stackSize; if (ic == null) ic = s; }
        }
        if (n > 0) {
            String t = n + " blocks left";
            float w = 18 + wid(t);
            render.item(ic, d[0] / 2f - w / 2f, d[1] / 2f + 70, 1);
            txt(t, d[0] / 2f - w / 2f + 18, d[1] / 2f + 74, n <= 15 ? 0xFFFF5555 : n <= 30 ? 0xFFFFFF55 : -1);
        }
    }
    if (btn("Show Speed")) {
        Entity p = client.getPlayer();
        if (p != null) {
            Vec3 m = p.getMotion();
            String t = Math.round(Math.hypot(m.x, m.z) * 200) / 10.0 + " bps";
            txt(t, d[0] / 2f - wid(t) / 2f, d[1] / 2f + 88, -1);
        }
    }
    if (btn("Debug")) {
        Entity p = client.getPlayer();
        String x = btn("Ice Boost") && p != null && air ? " &7air: &e" + (client.time() - airT) + "ms" + (ice != -1 ? " &aON" : " &8off") : "";
        String t = util.color(stopped() ? "&cstopped" : "&7delay: &e" + (int) delay() + "ms" + (client.isDiagonal() ? " &7(diag)" : rmb() ? " &7(rmb)" : "") + x);
        render.text(t, d[0] / 2f - render.getFontWidth(t) / 2f, d[1] / 2f + 10, 1, -1, true);
    }
}

void box(Vec3 p, Vec3 cam, int r, int g, int b, int a) {
    double x0 = p.x - cam.x, y0 = p.y - cam.y, z0 = p.z - cam.z;
    double[] v = {x0, y0, z0, x0 + 1, y0, z0, x0 + 1, y0, z0 + 1, x0, y0, z0 + 1, x0, y0 + 1, z0, x0 + 1, y0 + 1, z0, x0 + 1, y0 + 1, z0 + 1, x0, y0 + 1, z0 + 1};
    int[] q = {0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 5, 4, 3, 2, 6, 7, 0, 3, 7, 4, 1, 2, 6, 5};
    int[] e = {0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7};
    gl.color(r, g, b, (int) (a * 0.35));
    gl.begin(7);
    for (int i = 0; i < q.length; i++) gl.vertex3(v[q[i] * 3], v[q[i] * 3 + 1], v[q[i] * 3 + 2]);
    gl.end();
    gl.lineWidth(2);
    gl.color(r, g, b, a);
    gl.begin(1);
    for (int i = 0; i < e.length; i++) gl.vertex3(v[e[i] * 3], v[e[i] * 3 + 1], v[e[i] * 3 + 2]);
    gl.end();
}

void revert() { if (ice == -1 && saved > -1 && slot() != saved) set(saved); }
int slot() { return inventory.getSlot(); }
void set(int i) { inventory.setSlot(i); }
ItemStack inv(int i) { return inventory.getStackInSlot(i); }
boolean rmb() { return rmb || keybinds.isMouseDown(1); }
boolean stopped() { return btn("Stop on Shift") && client.isSneak(); }
double delay() { return client.isDiagonal() ? sld("Diagonal Delay") : btn("Tower") && rmb() ? 0 : sld("Delay"); }
boolean bl(String n) { String l = n.toLowerCase(); for (String b : BL) if (l.contains(b)) return true; return false; }
boolean blHeld(int s) { ItemStack i = inv(s); return i != null && bl(i.name); }
boolean bad(int s) { ItemStack i = inv(s); return i == null || i.stackSize == 0 || !i.isBlock || bl(i.name); }
int safe() { for (int i = 0; i < 9; i++) if (!bad(i)) return i; return -1; }
int findIce() { for (int i = 0; i < 9; i++) { ItemStack s = inv(i); if (s != null && s.stackSize > 0 && s.isBlock && s.name.toLowerCase().contains("ice")) return i; } return -1; }
boolean inBL(Entity p) {
    Vec3 pos = p.getPosition().floor();
    for (int x = -1; x < 2; x++)
        for (int y = 0; y < 2; y++)
            for (int z = -1; z < 2; z++)
                if (bl(world.getBlockAt((int) pos.x + x, (int) pos.y + y, (int) pos.z + z).name)) return true;
    return false;
}
float smooth(float t) { return t * t * (3 - 2 * t); }
int color() { Object o = modules.getColor(scriptName, "Highlight Color"); return o instanceof Color ? ((Color) o).getRGB() : o instanceof Integer ? (Integer) o : 0x6464FF64; }
void font() { try { String f = modules.getSliderString(scriptName, "Font"); if (f == null || f.isEmpty()) f = "Sf-Bold"; if (!f.equals(fName)) { fName = f; fr = mindless.utility.font.FontManager.getHudRenderer(f, 1f); } } catch (Exception e) {} }
void txt(String t, float x, float y, int c) { if (fr != null) fr.drawString(t, x, y, c, true); else render.text(t, x, y, 1, c, true); }
float wid(String t) { return fr != null ? (float) fr.getStringWidth(t) : render.getFontWidth(t); }

void clutch(boolean on) {
    if (on == modules.isEnabled(CLUTCH)) return;
    if (on) { clearBind(); modules.enable(CLUTCH); } else modules.disable(CLUTCH);
}

void jump(boolean on) {
    String n = jumpMod();
    if (n == null) return;
    if (on != modules.isEnabled(n)) { if (on) modules.enable(n); else modules.disable(n); }
}

void push() {
    for (String n : new String[]{"Reach", "Speed", "Snapback Speed", "Max distance", "Rotation Tolerance", "Minimum fall distance"}) {
        double v = sld(n);
        if (modules.getSlider(CLUTCH, n) != v) modules.setSlider(CLUTCH, n, v);
    }
    boolean v = btn("Simulate future position");
    if (modules.getButton(CLUTCH, "Simulate future position") != v) modules.setButton(CLUTCH, "Simulate future position", v);
    modules.setButton(CLUTCH, "Auto Clutch", true);
}

String jumpMod() {
    if (jm != null) return jm;
    for (List<String> l : modules.getCategories().values())
        for (String n : l)
            if (n.toLowerCase().replace(" ", "").equals("autojump")) return jm = n;
    for (List<String> l : modules.getCategories().values())
        for (String n : l)
            if (n.toLowerCase().contains("jump")) return jm = n;
    return null;
}

void clearBind() {
    try {
        if ("NONE".equals(modules.getSliderString(CLUTCH, "Select Keybind"))) return;
        for (int i = 0; i < 150; i++) {
            modules.setSlider(CLUTCH, "Select Keybind", i);
            if ("NONE".equals(modules.getSliderString(CLUTCH, "Select Keybind"))) return;
        }
    } catch (Exception e) {}
}

Vec3 placedPos(C08 c) {
    Vec3 p = c.position;
    int d = c.direction;
    return new Vec3(p.x + (d == 4 ? -1 : d == 5 ? 1 : 0), p.y + (d == 0 ? -1 : d == 1 ? 1 : 0), p.z + (d == 2 ? -1 : d == 3 ? 1 : 0));
}

boolean btn(String n) { return modules.getButton(scriptName, n); }
double sld(String n) { return modules.getSlider(scriptName, n); }