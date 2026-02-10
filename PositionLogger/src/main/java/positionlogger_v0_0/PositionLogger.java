package positionlogger_v0_0;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PositionLogger implements ModInitializer {

    private FileWriter writer;
    private boolean headerWritten = false;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm_SSS");

    @Override
    public void onInitialize() {

        // Register per-tick callback (20 TPS)
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // Close and reset writer on world/server shutdown
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            try {
                if (writer != null) {
                    writer.close();
                    writer = null;
                    headerWritten = false;
                    System.out.println("[PositionLogger] File closed");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        System.out.println("[PositionLogger] Loaded");
    }

    private void onServerTick(MinecraftServer server) {
        try {
            // Lazily open file on first tick of a world/session
            if (writer == null) {
                Path logPath = FabricLoader.getInstance()
                        .getGameDir()
                        .resolve("position_log.csv");
                writer = new FileWriter(logPath.toFile(), true);
                writeHeaderIfNeeded();
            }

            // Stable tick counter (increments every server tick)
            long tick = server.overworld().getGameTime();
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

            // Log each player's position
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {

                String id = "0_" + tick + "__" + timestamp;

                writer.write(
                        id + "," +
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

    private void writeHeaderIfNeeded() throws IOException {
        if (!headerWritten) {
            writer.write(
                    "ID," +
                    "PlayerCords_XPos," +
                    "PlayerCords_YPos," +
                    "PlayerCords_ZPos\n"
            );
            headerWritten = true;
        }
    }
}
