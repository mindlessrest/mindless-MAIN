package mindless.module.impl.player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import mindless.module.impl.bedwars.ResourceDepositTest;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import org.junit.*;
import static org.junit.Assert.*;

public class SilentRuntimeSmokeTest {
    private static ResourceDepositTest fixture;
    private static Minecraft mc;
    private SilentBedBreaker silent;

    @BeforeClass public static void initialize() throws Exception {
        fixture = new ResourceDepositTest();
        fixture.setUp();
        mc = Minecraft.getMinecraft();
        Method allocate = ResourceDepositTest.class.getDeclaredMethod("allocate", Class.class);
        allocate.setAccessible(true);
        mc.thePlayer = (TestPlayer) allocate.invoke(null, TestPlayer.class);
        mc.thePlayer.inventory = new InventoryPlayer(mc.thePlayer);
        field(EntityLivingBase.class, "activePotionsMap").set(mc.thePlayer, new HashMap<Integer, PotionEffect>());
    }

    @AfterClass public static void finish() throws Exception { fixture.tearDown(); }

    @Before public void reset() throws Exception {
        BedAura owner = new BedAura();
        silent = (SilentBedBreaker) field(BedAura.class, "silent").get(owner);
        mc.thePlayer.inventory.mainInventory[0] = null;
    }

    @Test public void handSpeedRemainsOne() throws Exception {
        assertEquals(1.0F, speed(Blocks.wool.getDefaultState(), true), 0.0001F);
    }

    @Test public void diamondPickaxeUsesVanillaStrength() throws Exception {
        mc.thePlayer.inventory.mainInventory[0] = new ItemStack(Items.diamond_pickaxe);
        assertEquals(8.0F, speed(Blocks.stone.getDefaultState(), true), 0.0001F);
    }

    @Test public void shearsRetainWoolSpeed() throws Exception {
        mc.thePlayer.inventory.mainInventory[0] = new ItemStack(Items.shears);
        assertEquals(5.0F, speed(Blocks.wool.getDefaultState(), true), 0.0001F);
    }

    @Test public void efficiencyAndAirbornePenaltyArePreserved() throws Exception {
        ItemStack axe = new ItemStack(Items.wooden_axe);
        axe.addEnchantment(net.minecraft.enchantment.Enchantment.efficiency, 2);
        mc.thePlayer.inventory.mainInventory[0] = axe;
        assertEquals(7.0F, speed(Blocks.planks.getDefaultState(), true), 0.0001F);
        assertEquals(1.4F, speed(Blocks.planks.getDefaultState(), false), 0.0001F);
    }

    @Test public void savedRangeMigratesToTheRequestedFourAndAHalfCap() throws Exception {
        mindless.module.setting.impl.ProfiledSliderSetting range =
                (mindless.module.setting.impl.ProfiledSliderSetting) field(SilentBedBreaker.class, "range").get(silent);
        com.google.gson.JsonObject profile = new com.google.gson.JsonObject();
        profile.addProperty("Silent.Range", 5.5);
        range.loadProfile(profile);
        Method method = SilentBedBreaker.class.getDeclaredMethod("effectiveReach");
        method.setAccessible(true);
        assertEquals(4.5, range.getInput(), 0.0);
        assertEquals(4.5, range.getMax(), 0.0);
        assertEquals(4.5, (Double) method.invoke(silent), 0.0);
    }

    private float speed(IBlockState state, boolean grounded) throws Exception {
        Method method = SilentBedBreaker.class.getDeclaredMethod("digSpeed", IBlockState.class, int.class, boolean.class);
        method.setAccessible(true);
        return (Float) method.invoke(silent, state, 0, grounded);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static class TestPlayer extends EntityPlayerSP {
        private TestPlayer() { super(null, null, null, null); }
        @Override public boolean isInsideOfMaterial(Material material) { return false; }
    }
}
