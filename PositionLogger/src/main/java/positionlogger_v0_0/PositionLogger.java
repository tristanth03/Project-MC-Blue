package positionlogger_v0_0;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class PositionLogger implements ModInitializer {

    private FileWriter writer;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        System.out.println("[PositionLogger] Loaded");
    }

    private void onServerTick(MinecraftServer server) {
        try {
            if (writer == null) {
                Path logPath = server.getRunDirectory()
                        .resolve("position_log.csv");
                writer = new FileWriter(logPath.toFile(), true);
            }

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                writer.write(
                    server.getTicks() + "," +
                    player.getX() + "," +
                    player.getY() + "," +
                    player.getZ() + "\n"
                );
            }

            writer.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
