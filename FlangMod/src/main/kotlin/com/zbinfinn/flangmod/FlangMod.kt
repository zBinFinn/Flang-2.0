package com.zbinfinn.flangmod

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import org.incendo.cloud.annotations.AnnotationParser
import org.incendo.cloud.annotations.Command
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.fabric.FabricClientCommandManager
import org.incendo.cloud.meta.CommandMeta

class FlangMod : ClientModInitializer {
    override fun onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, registryAccess ->
            // Create a Cloud manager that registers into the *client* dispatcher
            val manager =
                FabricClientCommandManager.createNative(ExecutionCoordinator.simpleCoordinator())

            // Parse @Command annotations
            val parser = AnnotationParser(
                manager,
                FabricClientCommandSource::class.java
            ) { CommandMeta.empty() }

            parser.parse(Commands())
        }
    }

    class Commands {
        @Command("runall clipboard")
        fun runAllFromClipboard() {
            var clipboard: String;
            try {
                clipboard = Minecraft.getInstance().keyboardHandler.clipboard;
            } catch (ignored: NullPointerException) {
                return;
            }

            for (str in clipboard.split('\n')) {
                Minecraft.getInstance().connection?.sendCommand(str.removePrefix("/"))
            }
        }
    }
}
