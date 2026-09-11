package mindless.module.impl.combat.aura;

import com.google.gson.*;
import mindless.module.impl.combat.KillAura;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.*;
import mindless.runtime.CombatPacketState;
import mindless.lag.api.*;
import mindless.lag.service.PacketDelayService;
import mindless.utility.profile.ProfileMigrations;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static mindless.module.impl.combat.aura.AuraAutoBlockController.*;

public class AuraPortTest {
    @Test public void everyFixtureSettingMatchesItsSourceDefault() throws Exception {
        JsonObject fixture = new JsonParser().parse(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("docs/KILLAURA_PREDAC_DEFAULTS.json")), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("KillAura");
        KillAura aura = new KillAura();
        String[] fields = {"mode","sort","autoBlock","autoBlockRequirePress","autoBlockNoSlow","autoBlockHold",
                "autoBlockDelay","autoBlockHurtTime","autoBlockRange","swingRange","attackRange","fov","minAps","maxAps",
                "switchDelay","rotationMode","moveFix","smoothing","angleStep","throughWalls","requirePress","allowMining",
                "weaponsOnly","allowTools","inventoryCheck","botCheck","players","bosses","mobs","animals","golems",
                "silverfish","teams","showTarget","debugLog"};
        for (String field : fields) {
            String key = field.equals("rotationMode") ? "rotations" : field.replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT);
            Setting setting = (Setting) KillAura.class.getField(field).get(aura);
            JsonElement expected = fixture.get(key);
            assertNotNull(key, expected);
            if (setting instanceof ButtonSetting) assertEquals(key, expected.getAsBoolean(), ((ButtonSetting)setting).isToggled());
            else if (((SliderSetting)setting).isString) assertEquals(key, expected.getAsString(), ((SliderSetting)setting).getSelectedOption().replace(' ', '_').toUpperCase(Locale.ROOT));
            else assertEquals(key, expected.getAsDouble(), ((SliderSetting)setting).getInput(), .00001);
        }
        assertEquals(38, fixture.entrySet().size());
    }

    @Test public void preferenceOrderKeepsRangeBeforeEnemiesAndDoesNotCapTargets() {
        AuraTarget near = candidate(2.5,true,false), farEnemy = candidate(4,true,true), occluded = candidate(2,false,true);
        List<AuraTarget> targets = new ArrayList<>(Arrays.asList(farEnemy,occluded,near));
        AuraTargeting.prefer(targets,3.2,3);
        assertEquals(Collections.singletonList(near),targets);
        targets = new ArrayList<>();for(int i=0;i<8;i++) targets.add(candidate(2.5,true,false));
        AuraTargeting.prefer(targets,3.2,3);assertEquals(8,targets.size());
        assertTrue(AuraTargeting.healthScore(10,10)<AuraTargeting.healthScore(8,5));
        assertEquals(Double.POSITIVE_INFINITY,AuraTargeting.healthScore(8,0),0);
        targets.sort(AuraTargeting.comparator(0));assertEquals(8,targets.size());
    }

    private static AuraTarget candidate(double distance, boolean usable, boolean enemy) {
        return new AuraTarget(null,new AxisAlignedBB(0,0,0,1,2,1),0,0,0,0,0,distance,0,usable,enemy);
    }

    @Test public void switchingWaitsForDelayRefreshesTheSnapshotAndAdvancesOnce() throws Exception {
        KillAura aura=new KillAura();aura.sort.setValue(0);
        java.lang.reflect.Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        net.minecraft.entity.EntityLivingBase first=(net.minecraft.entity.EntityLivingBase)unsafe.allocateInstance(net.minecraft.entity.monster.EntityZombie.class);
        net.minecraft.entity.EntityLivingBase second=(net.minecraft.entity.EntityLivingBase)unsafe.allocateInstance(net.minecraft.entity.monster.EntityZombie.class);
        AuraTarget a=new AuraTarget(first,new AxisAlignedBB(0,0,0,1,2,1),0,0,0,0,0,2,0,true);
        AuraTarget refreshed=new AuraTarget(first,a.bounds,0,0,0,0,0,2.1,0,true);
        AuraTarget b=new AuraTarget(second,a.bounds,0,0,0,0,0,2.5,0,true);
        java.lang.reflect.Method select=KillAura.class.getDeclaredMethod("selectCandidates",List.class,long.class);select.setAccessible(true);
        java.lang.reflect.Field current=KillAura.class.getDeclaredField("currentTarget");current.setAccessible(true);
        java.lang.reflect.Field attacked=KillAura.class.getDeclaredField("attackedSinceSelection");attacked.setAccessible(true);
        select.invoke(aura,new ArrayList<>(Arrays.asList(a,b)),1000L);assertSame(a,current.get(aura));
        attacked.setBoolean(aura,true);
        select.invoke(aura,new ArrayList<>(Arrays.asList(refreshed,b)),1050L);assertSame(refreshed,current.get(aura));
        assertTrue(attacked.getBoolean(aura));
        select.invoke(aura,new ArrayList<>(Arrays.asList(refreshed,b)),1070L);assertSame(b,current.get(aura));assertFalse(attacked.getBoolean(aura));
        select.invoke(aura,new ArrayList<>(Collections.singletonList(a)),1090L);assertSame(a,current.get(aura));
    }

    @Test public void onlyAcceptedMatchingAttacksAdvanceAndReplayCannotAdvanceAgain() throws Exception {
        CombatPacketState.beginSession();
        C02PacketUseEntity packet=new C02PacketUseEntity();
        net.minecraft.network.PacketBuffer buffer=new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeVarIntToBuffer(42);buffer.writeEnumValue(C02PacketUseEntity.Action.ATTACK);packet.readPacketData(buffer);
        } finally {buffer.release();}
        CombatPacketState.beginAuraAttack(42);assertFalse(CombatPacketState.consumeAuraAttackAccepted(42));
        CombatPacketState.beginAuraAttack(41);CombatPacketState.recordAccepted(packet);assertFalse(CombatPacketState.consumeAuraAttackAccepted(41));
        PacketDelayService service=new PacketDelayService(()->0,Runnable::run,null);
        DelayLease lease=service.acquire(DelayRequest.auraAutoBlock());
        CombatPacketState.beginAuraAttack(42);
        service.handleOutbound(packet,service.getCurrentEpoch(),p->{if(!CombatPacketState.consumeReplay(p))CombatPacketState.recordAccepted(p);});
        assertTrue(CombatPacketState.consumeAuraAttackAccepted(42));
        CombatPacketState.beginAuraAttack(42);lease.release();assertFalse(CombatPacketState.consumeAuraAttackAccepted(42));
    }

    @Test public void worldReplacementDropsTheOldAuraQueue() {
        PacketDelayService service=new PacketDelayService(()->0,Runnable::run,null);
        DelayLease lease=service.acquire(DelayRequest.auraAutoBlock());List<Packet<?>> delivered=new ArrayList<>();
        service.handleOutbound(new C03PacketPlayer(),service.getCurrentEpoch(),delivered::add);
        service.advanceWorld();lease.release();assertTrue(delivered.isEmpty());
        assertEquals(0,service.getPendingCount(EnumLagDirection.OUTBOUND));
    }

    @Test public void healthObservationsAreLocalSessionBoundAndConsumedOnce() {
        long epoch=CombatPacketState.beginSession();
        CombatPacketState.recordHealth(new net.minecraft.network.play.server.S06PacketUpdateHealth(18,20,5),3,20,epoch);
        assertTrue(CombatPacketState.consumeHurt());assertFalse(CombatPacketState.consumeHurt());
        assertEquals(-2,CombatPacketState.consumeHealthDelta(),0);assertNull(CombatPacketState.consumeHealthDelta());
        CombatPacketState.beginSession();
        CombatPacketState.recordHealth(new net.minecraft.network.play.server.S06PacketUpdateHealth(15,20,5),3,20,epoch);
        assertFalse(CombatPacketState.consumeHurt());assertNull(CombatPacketState.consumeHealthDelta());
    }
    private static final class Actions implements AuraAutoBlockController.Actions {
        final List<String> log = new ArrayList<>();
        boolean blocking, clear = true, slots = true, accept = true, alternate;
        int mode;
        public boolean blocking() { return blocking; }
        public boolean clearInterval() { return clear; }
        public boolean slotsMatch() { return slots; }
        public boolean release(boolean slot) {
            log.add(slot ? "slot" : "release");
            if (accept) { blocking = false; clear = false; }
            return accept;
        }
        public boolean spoof() { log.add("spare"); log.add("original"); blocking = false; return accept; }
        public boolean swapSword() {
            if (!alternate) return release(false);
            log.add("sword"); log.add("block"); clear = false; return accept;
        }
        public boolean attack() {
            if (!clear || blocking && mode != VANILLA) return false;
            log.add("attack"); return accept;
        }
        public boolean block(boolean attacked) {
            if (attacked) { log.add("interact_at"); log.add("interact"); }
            log.add("block"); blocking = accept; return accept;
        }
        public void releaseBuffer() { }
        void tick(AuraAutoBlockController controller, int selectedMode) {
            mode = selectedMode; clear = true; log.clear();
            controller.step(mode, true, true, true, 0, 6, 75, 0, this);
        }
    }

    @Test public void signedFourteenApsRemainders() {
        long cooldown = 0;
        long[] expected = {71,21,42,63,13,34,55,5,26};
        for (long next : expected) {
            cooldown = AuraTiming.decrement(cooldown);
            if (cooldown <= 0) cooldown = AuraTiming.addAttackDelay(cooldown,14,14,null);
            assertEquals(next,cooldown);
        }
        assertEquals(-29,AuraTiming.decrement(-29));
    }

    @Test public void legitUsesReleaseOnlyPassAndInteractionOrder() {
        Actions a = new Actions(); AuraAutoBlockController c = new AuraAutoBlockController();
        a.tick(c,LEGIT);
        assertEquals(Arrays.asList("attack","interact_at","interact","block"),a.log);
        a.tick(c,LEGIT); assertTrue(a.log.isEmpty()); assertEquals(25,c.hold);
        a.tick(c,LEGIT); assertEquals(Collections.singletonList("release"),a.log); assertEquals(-25,c.hold);
        a.tick(c,LEGIT); assertEquals(Arrays.asList("attack","interact_at","interact","block"),a.log);
    }

    @Test public void allModesHaveTheirDistinctTransitions() {
        for (int mode=NONE;mode<=FAKE;mode++) {
            Actions a=new Actions(); AuraAutoBlockController c=new AuraAutoBlockController();
            a.tick(c,mode);
            assertEquals(mode!=NONE && mode!=FAKE,c.active);
            assertEquals(mode!=NONE && mode!=VANILLA && mode!=SPOOF,c.render);
            assertEquals(mode==HYPIXEL || mode==BLINK || mode==INTERACT,c.restartPending);
            a.tick(c,mode);
            if (mode==BLINK) assertEquals(Collections.singletonList("release"),a.log);
            else if (mode==INTERACT) assertEquals(Collections.singletonList("slot"),a.log);
            else if (mode==VANILLA) assertEquals(Collections.singletonList("attack"),a.log);
            else assertTrue("mode="+mode,a.log.isEmpty());
            a.tick(c,mode);
            if (mode==SPOOF) assertEquals(Arrays.asList("spare","original","attack","interact_at","interact","block"),a.log);
            if (mode==SWAP || mode==LEGIT || mode==HYPIXEL) assertEquals(Collections.singletonList("release"),a.log);
        }
    }

    @Test public void delaySuppressionUsesThePreDecrementValue() {
        Actions a=new Actions(); AuraAutoBlockController c=new AuraAutoBlockController();
        c.releaseDelay=50;
        c.step(LEGIT,true,true,true,0,6,75,50,a);
        assertEquals(0,c.releaseDelay); assertTrue(c.active); assertTrue(c.render);
        assertFalse(a.log.contains("block"));
        a.tick(c,LEGIT); assertTrue(a.log.contains("block"));
    }

    @Test public void fakeAndNonePreserveIndependentManualUse() {
        for(int mode:new int[]{NONE,FAKE}) {
            Actions a=new Actions();AuraAutoBlockController c=new AuraAutoBlockController();
            c.step(mode,true,true,false,0,6,75,0,a);
            assertFalse(a.log.contains("block"));
            a.log.clear(); c.step(mode,true,true,true,0,6,75,0,a);
            assertTrue(a.log.contains("block"));assertFalse(c.active);
        }
    }

    @Test public void swapFindsAlternateSwordAndRejectedBlockRestartsCleanly() {
        Actions a=new Actions();AuraAutoBlockController c=new AuraAutoBlockController();
        a.alternate=true;a.tick(c,SWAP);a.tick(c,SWAP);a.tick(c,SWAP);
        assertEquals(Arrays.asList("sword","block"),a.log);
        c.reset();a.blocking=false;a.accept=false;a.tick(c,HYPIXEL);
        assertEquals(0,c.phase);assertEquals(0,c.hold);assertFalse(c.restartPending);
    }

    @Test public void hurtAndSuspensionClearOwnedPhases() {
        for(int mode=VANILLA;mode<=LEGIT;mode++) {
            Actions a=new Actions();AuraAutoBlockController c=new AuraAutoBlockController();a.tick(c,mode);
            a.clear=true;a.log.clear();c.step(mode,true,true,true,7,6,75,0,a);
            assertTrue(c.active);assertTrue(c.render);assertFalse(a.log.contains("attack"));
            a.clear=true;c.step(mode,false,false,false,0,6,75,0,a);
            assertFalse(c.active);assertFalse(c.render);assertFalse(c.restartPending);assertEquals(0,c.phase);
        }
    }

    @Test public void rejectedAndBusyCleanupRetainOwnershipAndPreventNewActions() {
        Actions a = new Actions();
        AuraAutoBlockController c = new AuraAutoBlockController();
        c.active = a.blocking = true;
        a.clear = false;
        c.step(LEGIT, true, false, false, 0, 6, 75, 0, a);
        assertTrue(c.active); assertTrue(c.cleanupPending); assertTrue(a.log.isEmpty());
        a.clear = true; a.accept = false;
        for (int i = 0; i < 25; i++) c.step(LEGIT, true, true, false, 0, 6, 75, 0, a);
        assertEquals(20, a.log.size());
        assertEquals(Collections.singleton("release"), new HashSet<>(a.log));
        assertTrue(c.active); assertTrue(c.cleanupPending);
        a.blocking = false;
        c.step(LEGIT, false, false, false, 0, 6, 75, 0, a);
        assertFalse(c.active); assertFalse(c.cleanupPending);
    }

    @Test public void defaultsPrecisionMalformedValuesAndReset() {
        KillAura aura=new KillAura();
        assertEquals(36,aura.getSettings().size());
        assertTrue(aura.killNotification.isToggled());
        assertEquals(7,aura.autoBlock.getInput(),0);assertEquals(3.1,aura.autoBlockRange.getInput(),0);
        assertEquals(3.2,aura.swingRange.getInput(),0);assertEquals(1.5,aura.autoBlockHold.getInput(),0);
        assertEquals(70,aura.switchDelay.getInput(),0);assertTrue(aura.autoBlockRequirePress.isToggled());
        JsonObject json=new JsonObject();json.addProperty("Min APS",18);json.addProperty("Max APS",10);
        json.addProperty("Range (attack)",4);json.addProperty("Range (swing)",3);
        json.addProperty("Auto block mode",99);json.addProperty("Auto block hold",Double.NaN);
        json.addProperty("Weapon only","false");json.addProperty("FOV",-1);
        aura.loadSettings(json);
        assertEquals(18,aura.maxAps.getInput(),0);assertEquals(4,aura.swingRange.getInput(),0);
        assertEquals(8,aura.autoBlock.getInput(),0);assertEquals(1.5,aura.autoBlockHold.getInput(),0);
        assertTrue(aura.weaponsOnly.isToggled());assertEquals(30,aura.fov.getInput(),0);
        for(Setting s:aura.getSettings())s.resetToDefault();
        assertEquals(14,aura.minAps.getInput(),0);assertEquals(7,aura.autoBlock.getInput(),0);
        JsonObject saved=new JsonObject();
        for(Setting s:aura.getSettings()) {
            if(s instanceof SliderSetting)saved.addProperty(s.getProfileKey(),((SliderSetting)s).getInput());
            else saved.addProperty(s.getProfileKey(),((ButtonSetting)s).isToggled());
        }
        aura.loadSettings(saved);
        assertEquals(3.1,aura.autoBlockRange.getInput(),0);assertEquals(3.2,aura.swingRange.getInput(),0);
        aura.swingRange.setValue(3);aura.settingsEdited();assertEquals(3,aura.attackRange.getInput(),0);
        aura.maxAps.setValue(10);aura.settingsEdited();assertEquals(10,aura.minAps.getInput(),0);
    }

    @Test public void legacyMigrationPreservesMetadataAndNewModeWins() {
        JsonObject profile=new JsonObject();profile.addProperty("configVersion",3);
        JsonArray modules=new JsonArray();JsonObject aura=new JsonObject();modules.add(aura);profile.add("modules",modules);
        aura.addProperty("name","Kill Aura");aura.addProperty("enabled",false);aura.addProperty("keybind",44);
        aura.addProperty("hidden",true);aura.addProperty("Target CPS",12.5);aura.addProperty("Sort mode",4);
        aura.addProperty("Auto block",true);aura.addProperty("unknown","keep");
        assertTrue(ProfileMigrations.migrate(profile,null,"test"));
        assertEquals(12,aura.get("Min APS").getAsInt());assertEquals(12,aura.get("Max APS").getAsInt());
        assertEquals(1,aura.get("Sort mode").getAsInt());assertEquals(7,aura.get("Auto block mode").getAsInt());
        assertFalse(aura.get("enabled").getAsBoolean());assertEquals(44,aura.get("keybind").getAsInt());
        assertTrue(aura.get("hidden").getAsBoolean());assertEquals("keep",aura.get("unknown").getAsString());
        assertFalse(ProfileMigrations.migrate(profile,null,"test"));
        profile.addProperty("configVersion",3);aura.addProperty("Auto block mode",8);aura.addProperty("Auto block",false);
        ProfileMigrations.migrate(profile,null,"test");assertEquals(8,aura.get("Auto block mode").getAsInt());
    }

    @Test public void boundsDistanceAndRotationLimits() {
        AxisAlignedBB box=new AxisAlignedBB(3,-1,-1,5,1,1);Vec3 eyes=new Vec3(0,0,0);
        assertEquals(3,AuraTargeting.distanceToBounds(box,eyes),0);
        assertEquals(0,AuraTargeting.distanceToBounds(box,new Vec3(4,0,0)),0);
        Random fixed=new Random(){@Override public float nextFloat(){return .5f;}};
        float[] result=AuraTargeting.rotate(0,0,170,90,180,0,.5f,fixed);
        assertEquals(157.5,result[0],.16);assertEquals(78.75,result[1],.16);
        result=AuraTargeting.rotate(0,0,170,90,180,100,.5f,fixed);
        assertEquals(78.75,result[0],.16);assertEquals(39.375,result[1],.16);
    }

    @Test public void backgroundPacketsDoNotReplaceTheCallingThreadsAcceptance() throws Exception {
        CombatPacketState.beginSession();
        C03PacketPlayer movement = new C03PacketPlayer();
        CombatPacketState.recordAccepted(movement);
        Thread background = new Thread(() -> CombatPacketState.recordAccepted(new C03PacketPlayer()));
        background.start();
        background.join();
        assertTrue(CombatPacketState.wasAccepted(movement));
        assertFalse(CombatPacketState.wasAccepted(new C03PacketPlayer()));
        CombatPacketState.beginSession();
        assertFalse(CombatPacketState.wasAccepted(movement));
    }

    @Test public void acceptedMovementResetsActionsButReplayDoesNot() {
        CombatPacketState.resetSession();
        C07PacketPlayerDigging release=new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,BlockPos.ORIGIN,EnumFacing.DOWN);
        CombatPacketState.recordAccepted(release);assertTrue(CombatPacketState.sentDigging());
        C03PacketPlayer movement=new C03PacketPlayer();
        assertTrue(CombatPacketState.sentDigging());
        CombatPacketState.recordAccepted(movement);assertFalse(CombatPacketState.sentDigging());
        CombatPacketState.recordAccepted(release);CombatPacketState.markReplay(movement);
        if(!CombatPacketState.consumeReplay(movement))CombatPacketState.recordAccepted(movement);
        assertTrue(CombatPacketState.sentDigging());assertFalse(CombatPacketState.consumeReplay(movement));
    }

    @Test public void bufferedAcceptanceAndOtherOwnersRemainIndependent() {
        CombatPacketState.resetSession();
        PacketDelayService service=new PacketDelayService(()->0,Runnable::run,null);
        DelayLease aura=service.acquire(DelayRequest.auraAutoBlock());
        List<Packet<?>> delivered=new ArrayList<>();
        C03PacketPlayer movement=new C03PacketPlayer();
        service.handleOutbound(movement,service.getCurrentEpoch(),delivered::add);
        assertTrue(CombatPacketState.wasAccepted(movement));assertTrue(delivered.isEmpty());
        C01PacketChatMessage chat=new C01PacketChatMessage("test");
        service.handleOutbound(chat,service.getCurrentEpoch(),delivered::add);
        assertEquals(Collections.singletonList(chat),delivered);
        DelayLease other=service.acquire(DelayRequest.perPacketMillis("other",EnumSet.of(EnumLagDirection.OUTBOUND),1000));
        assertTrue(service.hasOtherOutboundOwner("KillAuraAutoBlock"));
        C03PacketPlayer shared=new C03PacketPlayer();service.handleOutbound(shared,service.getCurrentEpoch(),delivered::add);
        aura.release();assertFalse(delivered.contains(shared));other.release();assertTrue(delivered.contains(shared));
    }

    @Test public void transactionBypassesOnlyAnEmptyAuraQueue() {
        DelayRequest r=DelayRequest.auraAutoBlock();
        assertFalse(r.claimsOutbound(new C00PacketKeepAlive(),true));
        assertFalse(r.claimsOutbound(new C0FPacketConfirmTransaction(),false));
        assertTrue(r.claimsOutbound(new C0FPacketConfirmTransaction(),true));
        assertTrue(r.claimsOutbound(new C03PacketPlayer(),false));
    }
}
