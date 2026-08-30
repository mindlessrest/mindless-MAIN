package mindless.runtime;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Guards the feature-level hooks that a target-class-only test cannot prove. */
public class FeatureCoverageContractTest {
    @Test
    public void everyRegisteredModuleAndSharedUiHasItsNativeHookContract() throws Exception {
        String manager = read("src/main/java/mindless/module/ModuleManager.java");
        Matcher registrations = Pattern.compile("addModule\\([^;]*?new\\s+[A-Za-z0-9_.]+\\s*\\(")
                .matcher(manager);
        int moduleCount = 0;
        while (registrations.find()) moduleCount++;
        Assert.assertTrue("Module registry unexpectedly shrank: " + moduleCount, moduleCount >= 130);

        Map<String, String[]> contracts = new LinkedHashMap<>();
        contracts.put("src/main/java/mindless/transformer/impl/client/TransformerMinecraft.java",
                new String[]{"GameTickEvent", "PreAttackEvent", "SlotUpdateEvent",
                        "postGuiOpen", "directLunarWorldLoad", "handleSpecialKeyPresses"});
        contracts.put("src/main/java/mindless/transformer/impl/entity/TransformerEntity.java",
                new String[]{"forceSafeWalk", "StepHeightEvent", "ClientLookEvent", "PlayerMoveEvent"});
        contracts.put("src/main/java/mindless/transformer/impl/entity/TransformerEntityPlayerSP.java",
                new String[]{"PreMotionEvent", "PostMotionEvent", "beforeCloseScreen"});
        contracts.put("src/main/java/mindless/transformer/impl/entity/TransformerEntityLivingBase.java",
                new String[]{"LivingUpdateEvent", "JumpEvent", "PrePlayerMovementInputEvent"});
        contracts.put("src/main/java/mindless/transformer/impl/entity/TransformerEntityLiving.java",
                new String[]{"LivingSetAttackTargetEvent", "setAttackTarget"});
        contracts.put("src/main/java/mindless/transformer/impl/network/TransformerNetHandlerPlayClient.java",
                new String[]{"PreEntityVelocityEvent", "PreExplosionPacketEvent", "handlePlayerPosLook"});
        contracts.put("src/main/java/mindless/transformer/impl/client/TransformerWorld.java",
                new String[]{"setSkyColor", "clearWeather", "customTime"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerItemRenderer.java",
                new String[]{"ItemAnimationRuntime", "AlwaysBlock",
                        "isCancelUpdate"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerRendererLivingEntity.java",
                new String[]{"showInvisibleOutline", "setOutlineColor", "DamageTintRuntime",
                        "postRenderLivingSpecialsPre"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerRenderGlobal.java",
                new String[]{"setupTerrainPitch", "setupTerrainYaw", "setupTerrainVector",
                        "DrawBlockHighlightEvent"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerEntityRenderer.java",
                new String[]{"FogColors", "RenderFogEvent", "LightmapUpdateEvent",
                        "postRenderTick", "postRenderWorld"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerRenderPlayer.java",
                new String[]{"postRenderPlayerPre", "postRenderPlayerPost", "BlockAnimationUtils"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerGuiNewChat.java",
                new String[]{"drawChat", "ChatAnimationRuntime", "BlurUtils", "RoundedUtils"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerGuiIngame.java",
                new String[]{"renderScoreboard", "SpotifyMiniPlayerRenderer.render", "postOverlayPre",
                        "postOverlayPost"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerGuiIngameForge.java",
                new String[]{"SpotifyMiniPlayerRenderer.render", "clearScoreboard"});
        contracts.put("src/main/java/mindless/transformer/impl/render/TransformerGuiChat.java",
                new String[]{"ChatCommandRuntime", "drawSuggestions", "scrollSuggestions",
                        "suppressVanillaBackground"});
        contracts.put("src/main/java/mindless/runtime/LunarEventBridge.java",
                new String[]{"postClientTick", "postRenderTick", "postRenderWorld", "postOverlayPre",
                        "postGuiOpen", "postRenderPlayerPre", "postRenderLivingSpecialsPre"});

        for (Map.Entry<String, String[]> contract : contracts.entrySet()) {
            String source = read(contract.getKey());
            for (String required : contract.getValue()) {
                Assert.assertTrue(contract.getKey() + " is missing feature hook " + required,
                        source.contains(required));
            }
        }
    }

    @Test
    public void customEventsConsumedByFeaturesHaveAProducer() throws Exception {
        Path root = Paths.get("src", "main", "java", "mindless");
        StringBuilder production = new StringBuilder();
        StringBuilder consumers = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                            if (!path.toString().contains("\\event\\")) production.append(source).append('\n');
                            if (path.toString().contains("\\module\\impl\\")
                                    || path.endsWith("ScriptEvents.java")) consumers.append(source).append('\n');
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                    });
        }
        Matcher imports = Pattern.compile("import\\s+mindless\\.event\\.([A-Za-z0-9_]+);")
                .matcher(consumers);
        while (imports.find()) {
            String event = imports.group(1);
            Assert.assertTrue("No producer constructs custom event consumed by a feature: " + event,
                    production.toString().contains("new " + event + "("));
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
