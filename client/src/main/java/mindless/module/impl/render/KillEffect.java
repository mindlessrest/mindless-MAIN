package mindless.module.impl.render;

import mindless.effect.EffectSystem;
import mindless.effect.impl.BurstEffect;
import mindless.effect.impl.ReassembleEffect;
import mindless.event.PlayerKillEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.setting.impl.TextSetting;
import mindless.accountmanager.utils.ModernFileChooser;
import mindless.utility.Utils;
import mindless.utility.sound.ResourceMp3Player;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Something to look at when a player dies.
 *
 * Purely cosmetic, and built to stay that way: effects are pooled, drawn in one batch, and expire
 * on a timer, so a busy fight cannot turn into a slideshow. Nothing here touches the player, the
 * world or a packet.
 */
public class KillEffect extends Module {
    // Appended, never reordered: a dropdown persists by index, so moving one of these silently
    // changes what every saved profile means.
    private static final String[] MODES = new String[]{"Blood", "Lightning", "Soul", "XP burst", "Reassemble"};
    private static final int MODE_BLOOD = 0;
    private static final int MODE_LIGHTNING = 1;
    private static final int MODE_SOUL = 2;
    private static final int MODE_XP = 3;
    private static final int MODE_REASSEMBLE = 4;
    private static final String[] KILL_SOUNDS = new String[]{"Mommy ASMR", "Off", "Anime laugh", "Anime Giggle", "Anime Oi", "ohayou-gozaimassssssssu", "Custom"};
    private static final String[] KILL_SOUND_RESOURCES = new String[]{
            "/assets/mindless/sounds/mommy_asmr.mp3",
            null,
            "/assets/mindless/sounds/anime_laugh.mp3",
            "/assets/mindless/sounds/anime_giggle.mp3",
            "/assets/mindless/sounds/anime_oi.mp3",
            "/assets/mindless/sounds/ohayou_gozaimasu.mp3",
            null
    };
    private static final int KILL_SOUND_OFF = 1;
    private static final int KILL_SOUND_CUSTOM = 6;

    private static final int MAX_EFFECTS = 6;
    private static final int PARTICLES_PER_EFFECT = 28;

    private final SliderSetting mode;
    private final SliderSetting duration;
    private final SliderSetting size;
    private final SliderSetting killSound;
    private final SliderSetting killSoundVolume;
    private final TextSetting customKillSoundName;
    private final TextSetting customKillSoundPath;
    private final ButtonSetting useCustomColor;
    private final ColorSetting customColor;
    private final SliderSetting orbCount;
    private final SliderSetting orbSize;
    private final SliderSetting orbSpeed;

    private final Random random = new Random();
    private final List<Effect> effects = new ArrayList<Effect>();
    private int lastEffectEntityId = -1;
    private long lastEffectTimeMs;

    public KillEffect() {
        super("Kill Effect", "Plays an effect where a player dies.", category.render, 0);
        this.registerSetting(mode = new SliderSetting("Mode", MODE_BLOOD, MODES));
        this.registerSetting(duration = new SliderSetting("Duration", "s", 1.2, 0.3, 4.0, 0.1));
        this.registerSetting(size = new SliderSetting("Size", 1.0, 0.3, 3.0, 0.05));
        this.registerSetting(killSound = new SliderSetting("Kill Sound", 0, KILL_SOUNDS));
        this.registerSetting(killSoundVolume = new SliderSetting("Kill Sound Volume", "%", 35.0, 0.0, 100.0, 5.0));
        this.registerSetting(customKillSoundName = new TextSetting("Custom sound name", "Custom", "Preset name", 32));
        this.registerSetting(customKillSoundPath = new TextSetting("Custom sound path", "", "Choose an MP3 or WAV", 260));
        this.registerSetting(new ButtonSetting("Choose custom kill sound", new Runnable() {
            @Override
            public void run() {
                chooseCustomKillSound();
            }
        }));
        this.registerSetting(useCustomColor = new ButtonSetting("Custom color", false));
        this.registerSetting(customColor = new ColorSetting("Color", 220, 40, 40, 255));
        this.registerSetting(orbCount = new SliderSetting("Orb count", 42, 6, 120, 1));
        this.registerSetting(orbSize = new SliderSetting("Orb size", "blocks", 0.07, 0.02, 0.3, 0.01));
        this.registerSetting(orbSpeed = new SliderSetting("Orb speed", 0.09, 0.02, 0.3, 0.01));
    }

    private boolean usesOrbs() {
        int selected = (int) mode.getInput();
        return selected == MODE_XP || selected == MODE_REASSEMBLE;
    }

    @Override
    public void guiUpdate() {
        String customName = customKillSoundName == null ? "" : customKillSoundName.getText().trim();
        KILL_SOUNDS[KILL_SOUND_CUSTOM] = customName.isEmpty() ? "Custom" : customName;
        boolean customSound = killSound != null && (int) killSound.getInput() == KILL_SOUND_CUSTOM;
        if (customKillSoundName != null) {
            customKillSoundName.setVisible(customSound, this);
        }
        if (customKillSoundPath != null) {
            customKillSoundPath.setVisible(customSound, this);
        }
        if (killSoundVolume != null) {
            killSoundVolume.setVisible(killSound != null && (int) killSound.getInput() != KILL_SOUND_OFF, this);
        }
        if (customColor != null) {
            customColor.setVisible(useCustomColor != null && useCustomColor.isToggled(), this);
        }
        boolean orbs = usesOrbs();
        if (orbCount != null) {
            orbCount.setVisible(orbs, this);
        }
        if (orbSize != null) {
            orbSize.setVisible(orbs, this);
        }
        if (orbSpeed != null) {
            orbSpeed.setVisible(orbs, this);
        }
        if (size != null) {
            size.setVisible(!orbs, this);
        }
    }

    @Override
    public void onDisable() {
        effects.clear();
        lastEffectEntityId = -1;
        lastEffectTimeMs = 0L;
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()].toLowerCase();
    }

    @SubscribeEvent
    public void onPlayerKill(PlayerKillEvent event) {
        triggerEffect(event);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        drawEffects();
    }

    private void triggerEffect(PlayerKillEvent event) {
        long now = System.currentTimeMillis();
        if (event.entityId == lastEffectEntityId && now - lastEffectTimeMs < 2000L) {
            return;
        }
        lastEffectEntityId = event.entityId;
        lastEffectTimeMs = now;
        playKillSound();

        if (usesOrbs()) {
            spawnOrbs(event);
            return;
        }
        spawn(event.x, event.y, event.z);
    }

    private void chooseCustomKillSound() {
        ModernFileChooser.showOpenDialog(
                "Select custom kill sound", null, "Audio (*.mp3, *.wav)", new String[]{"mp3", "wav"},
                new java.util.function.Consumer<File>() {
                    @Override
                    public void accept(File file) {
                        String currentName = customKillSoundName.getText().trim();
                        if (currentName.isEmpty() || "Custom".equals(currentName)) {
                            String fileName = file.getName();
                            int dot = fileName.lastIndexOf('.');
                            customKillSoundName.setText(dot > 0 ? fileName.substring(0, dot) : fileName);
                        }
                        try {
                            file = installCustomKillSound(file);
                        }
                        catch (IOException error) {
                            Utils.sendMessage("&cCould not save the custom kill sound locally.");
                            return;
                        }
                        customKillSoundPath.setText(file.getAbsolutePath());
                        KILL_SOUNDS[KILL_SOUND_CUSTOM] = customKillSoundName.getText().trim();
                        killSound.setValueWithEvent(KILL_SOUND_CUSTOM);
                        Utils.sendMessage("&aCustom kill sound added as " + KILL_SOUNDS[KILL_SOUND_CUSTOM] + ".");
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        Utils.sendMessage("&7No custom kill sound chosen.");
                    }
                });
    }

    private File installCustomKillSound(File source) throws IOException {
        File directory = new File(mc.mcDataDir, "mindless/custom-kill-sounds");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create custom sound directory");
        }
        String sourceName = source.getName();
        int dot = sourceName.lastIndexOf('.');
        String extension = dot >= 0 ? sourceName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "mp3";
        String name = customKillSoundName.getText().trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty()) {
            name = "custom";
        }
        File target = new File(directory, name + "." + extension);
        if (!source.getCanonicalFile().equals(target.getCanonicalFile())) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private void playKillSound() {
        int selected = (int) killSound.getInput();
        float volume = (float) killSoundVolume.getInput() / 100.0f;
        if (selected == KILL_SOUND_CUSTOM) {
            String path = customKillSoundPath.getText().trim();
            if (!path.isEmpty()) {
                ResourceMp3Player.playFile(new File(path), volume);
            }
            return;
        }
        if (selected >= 0 && selected < KILL_SOUND_RESOURCES.length
                && KILL_SOUND_RESOURCES[selected] != null) {
            ResourceMp3Player.playResource(KILL_SOUND_RESOURCES[selected], volume);
        }
    }

    /**
     * The two modes that run on the shared effect system.
     *
     * The skin is read here rather than when the figure is drawn: the entity is removed from the
     * world the moment it dies, and by the time Reassemble reaches its last phase there is nothing
     * left to ask. Without a skin it falls back to orbs alone, which is the honest degradation.
     */
    private void spawnOrbs(PlayerKillEvent event) {
        int ticks = Math.max(1, (int) Math.round(duration.getInput() * 20.0));
        int rgb = useCustomColor.isToggled() ? (customColor.getRGB() & 0xFFFFFF) : 0x6FE04A;
        int count = (int) orbCount.getInput();
        double speed = orbSpeed.getInput();
        double orb = orbSize.getInput();

        if ((int) mode.getInput() == MODE_XP) {
            EffectSystem.spawn(new BurstEffect(event.x, event.y + 0.6, event.z, ticks,
                    count, speed, speed * 1.6, orb, rgb, true, false, random));
            return;
        }

        ResourceLocation skin = null;
        boolean slim = false;
        float yaw = 0.0f;
        if (event.player instanceof AbstractClientPlayer) {
            AbstractClientPlayer player = (AbstractClientPlayer) event.player;
            try {
                skin = player.getLocationSkin();
                slim = "slim".equals(player.getSkinType());
            }
            catch (Throwable unavailable) {
                skin = null;
            }
            yaw = player.rotationYaw;
        }

        // Reassemble is deliberately longer than the slider says: the scatter, the gather and the
        // hold each need room, and a one second version of it reads as a glitch.
        EffectSystem.spawn(new ReassembleEffect(event.x, event.y, event.z,
                Math.max(ticks, 40), count, speed, orb, rgb, yaw, skin, slim, random));
    }

    private void spawn(double x, double y, double z) {
        if (effects.size() >= MAX_EFFECTS) {
            effects.remove(0);
        }
        effects.add(new Effect(x, y, z, (int) mode.getInput(),
                (float) duration.getInput() * 1000.0f, (float) size.getInput(), random));
    }

    private void drawEffects() {
        if (effects.isEmpty()) {
            return;
        }

        double viewX = mc.getRenderManager().viewerPosX;
        double viewY = mc.getRenderManager().viewerPosY;
        double viewZ = mc.getRenderManager().viewerPosZ;
        long now = System.currentTimeMillis();
        int rgb = useCustomColor.isToggled() ? (customColor.getRGB() & 0xFFFFFF) : -1;

        GlStateManager.pushMatrix();
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        Iterator<Effect> iterator = effects.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            if (!effect.draw(worldRenderer, now, viewX, viewY, viewZ, rgb)) {
                iterator.remove();
            }
        }

        tessellator.draw();

        GlStateManager.depthMask(true);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** One burst. All three modes are the same particles under different starting conditions. */
    private static final class Effect {
        private final double x;
        private final double y;
        private final double z;
        private final int style;
        private final float lifeMs;
        private final float scale;
        private final long born;
        private final float[] dirX = new float[PARTICLES_PER_EFFECT];
        private final float[] dirY = new float[PARTICLES_PER_EFFECT];
        private final float[] dirZ = new float[PARTICLES_PER_EFFECT];
        private final float[] speed = new float[PARTICLES_PER_EFFECT];
        private final int[] tint = new int[PARTICLES_PER_EFFECT];

        Effect(double x, double y, double z, int style, float lifeMs, float scale, Random random) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.style = style;
            this.lifeMs = Math.max(100.0f, lifeMs);
            this.scale = scale;
            this.born = System.currentTimeMillis();

            for (int i = 0; i < PARTICLES_PER_EFFECT; i++) {
                double theta = random.nextDouble() * Math.PI * 2.0;
                double phi = Math.acos(2.0 * random.nextDouble() - 1.0);
                dirX[i] = (float) (Math.sin(phi) * Math.cos(theta));
                dirZ[i] = (float) (Math.sin(phi) * Math.sin(theta));
                dirY[i] = (float) Math.cos(phi);

                switch (style) {
                    case MODE_LIGHTNING:
                        // Squashed flat and thrown hard: a ring rather than a sphere.
                        dirY[i] *= 0.15f;
                        speed[i] = 2.4f + random.nextFloat() * 2.0f;
                        tint[i] = 0x99CCFF;
                        break;
                    case MODE_SOUL:
                        // Slow, always upward, wandering.
                        dirY[i] = 0.6f + random.nextFloat() * 0.5f;
                        dirX[i] *= 0.35f;
                        dirZ[i] *= 0.35f;
                        speed[i] = 0.5f + random.nextFloat() * 0.4f;
                        tint[i] = 0xCFE8FF;
                        break;
                    case MODE_BLOOD:
                    default:
                        speed[i] = 1.2f + random.nextFloat() * 1.6f;
                        tint[i] = random.nextFloat() < 0.3f ? 0xFF5555 : 0xAA0000;
                        break;
                }
            }
        }

        /** @return false once it has expired and should be dropped. */
        boolean draw(WorldRenderer worldRenderer, long now, double viewX, double viewY, double viewZ, int forced) {
            float age = (now - born) / lifeMs;
            if (age >= 1.0f) {
                return false;
            }
            float fade = 1.0f - age;
            float gravity = style == MODE_SOUL ? -0.15f : 1.6f;

            for (int i = 0; i < PARTICLES_PER_EFFECT; i++) {
                float travel = speed[i] * age * scale;
                double px = x + dirX[i] * travel;
                double py = y + 1.0 + dirY[i] * travel - gravity * age * age * scale;
                double pz = z + dirZ[i] * travel;

                float radius = 0.055f * scale * (style == MODE_LIGHTNING ? 1.0f + age : 1.0f - age * 0.4f);
                int rgb = forced >= 0 ? forced : tint[i];
                int alpha = Math.round(255 * fade * fade);
                if (alpha <= 2) {
                    continue;
                }
                quad(worldRenderer, px - viewX, py - viewY, pz - viewZ, radius, rgb, alpha);
            }
            return true;
        }

        /**
         * A camera-facing square.
         *
         * Built on the view matrix's own right and up vectors rather than on world axes, so it
         * stays square to the eye from any angle without a per-particle rotation.
         */
        private static void quad(WorldRenderer worldRenderer, double x, double y, double z,
                                 float radius, int rgb, int alpha) {
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;

            net.minecraft.client.renderer.entity.RenderManager manager =
                    net.minecraft.client.Minecraft.getMinecraft().getRenderManager();
            // Billboard basis from the render manager's rotation, which is the camera's.
            float yaw = manager.playerViewY;
            float pitch = manager.playerViewX;
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(pitch);

            double rightX = Math.cos(yawRad);
            double rightZ = Math.sin(yawRad);
            double upX = -Math.sin(yawRad) * Math.sin(pitchRad);
            double upY = Math.cos(pitchRad);
            double upZ = Math.cos(yawRad) * Math.sin(pitchRad);

            for (int corner = 0; corner < 6; corner++) {
                // Two triangles: 0,1,2 and 0,2,3.
                int index = corner < 3 ? corner : (corner == 3 ? 0 : corner - 1);
                double sx = (index == 0 || index == 3) ? -1 : 1;
                double sy = (index == 0 || index == 1) ? -1 : 1;
                worldRenderer
                        .pos(x + (rightX * sx + upX * sy) * radius,
                             y + (upY * sy) * radius,
                             z + (rightZ * sx + upZ * sy) * radius)
                        .color(red, green, blue, alpha)
                        .endVertex();
            }
        }
    }
}
