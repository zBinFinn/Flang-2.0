package com.zbinfinn.flangmod

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

class FlangMod : ClientModInitializer {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override fun onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, registryAccess ->
            dispatcher.register(LiteralArgumentBuilder.literal<FabricClientCommandSource>("runall").executes {
                val clipboard: String;
                try {
                    clipboard = Minecraft.getInstance().keyboardHandler.clipboard;
                } catch (ignored: NullPointerException) {
                    return@executes 1;
                }

                for (str in clipboard.split('\n')) {
                    Minecraft.getInstance().connection?.sendCommand(str.removePrefix("/").trimEnd())
                }
                return@executes 0
            })
            dispatcher.register(
                LiteralArgumentBuilder.literal<FabricClientCommandSource>("place_from_clipboard").executes {
                    val clipboard: String;
                    try {
                        clipboard = Minecraft.getInstance().keyboardHandler.clipboard;
                    } catch (ignored: NullPointerException) {
                        return@executes 1;
                    }
                    Thread.startVirtualThread {
                        CodeClientTalker.placeFromClipboard(clipboard)
                    }
                    return@executes 0
                })
        }
    }
}
