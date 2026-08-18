package mindless.transformer.impl.network;

import io.netty.buffer.ByteBuf;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.handshake.FMLHandshakeMessage;

import java.util.ArrayList;
import java.util.Map;

@CTransformer(FMLHandshakeMessage.ModList.class)
public abstract class TransformerModList {
    @CShadow
    private Map<String, String> modTags;

    @CInline
    @CInject(method = "toBytes", target = @CTarget("HEAD"), cancellable = true)
    public void toBytes(ByteBuf buffer, InjectionCallback callbackInfo) {
        if (Minecraft.getMinecraft().isSingleplayer()) return;
        callbackInfo.setCancelled(true);

        ArrayList<Map.Entry<String, String>> shownTags = new ArrayList<>();
        for (Map.Entry<String, String> modTag : this.modTags.entrySet()) {
            String modId = modTag.getKey();
            if ("FML".equals(modId) || "mcp".equals(modId) || "Forge".equals(modId)) {
                shownTags.add(modTag);
            }
        }
        ByteBufUtils.writeVarInt(buffer, shownTags.size(), 2);
        for (Map.Entry<String, String> modTag : shownTags) {
            ByteBufUtils.writeUTF8String(buffer, modTag.getKey());
            ByteBufUtils.writeUTF8String(buffer, modTag.getValue());
        }
    }
}
