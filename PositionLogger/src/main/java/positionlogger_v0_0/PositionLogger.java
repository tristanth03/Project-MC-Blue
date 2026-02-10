package positionlogger_v0_0;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PositionLogger implements ModInitializer {

    private FileWriter writer;
    private boolean headerWritten = false;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm_SSS");

    // Vision parameters (human-like)
    private static final double MAX_VIEW_DISTANCE = 64.0;

    // Foveal (~5°) and peripheral (~14°) cones
    private static final double FOVEAL_DOT = 0.996;
    private static final double PERIPHERAL_DOT = 0.97;

    // Peripheral awareness radius
    private static final double PERIPHERAL_RADIUS = 3.0;
    private static final double PERIPHERAL_DISTANCE_LIMIT = 8.0;

    @Override
    public void onInitialize() {

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

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
            if (writer == null) {
                Path logPath = FabricLoader.getInstance()
                        .getGameDir()
                        .resolve("position_log.csv");
                writer = new FileWriter(logPath.toFile(), true);
                writeHeaderIfNeeded();
            }

            long tick = server.overworld().getGameTime();
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {

                String id = "0_" + tick + "__" + timestamp;

                // Player position
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();

                // View information
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getLookAngle();

                // Player stats
                double health = player.getHealth();
                int food = player.getFoodData().getFoodLevel();

                // Biome (1.15-safe, player-perceivable)
                String biome = player.level()
                        .getBiome(player.blockPosition())
                        .toString();

                // Determine visible distance via block raycast
                HitResult blockHit = player.pick(MAX_VIEW_DISTANCE, 0.0f, false);
                double visibleDistance = blockHit.getType() == HitResult.Type.MISS
                        ? MAX_VIEW_DISTANCE
                        : blockHit.getLocation().distanceTo(eye);

                // Eyesight-volume mob detection
                boolean viewingMob = false;

                List<Entity> candidates = player.level().getEntities(
                        player,
                        player.getBoundingBox()
                                .expandTowards(look.scale(visibleDistance))
                                .inflate(PERIPHERAL_RADIUS),
                        e -> e instanceof Mob
                );

                for (Entity entity : candidates) {

                    Vec3 toEntity = entity.getBoundingBox()
                            .getCenter()
                            .subtract(eye);

                    double distance = toEntity.length();
                    if (distance > visibleDistance) continue;

                    Vec3 dir = toEntity.normalize();
                    double dot = look.dot(dir);

                    // Foveal vision (strong focus)
                    if (dot > FOVEAL_DOT) {
                        viewingMob = true;
                        break;
                    }

                    // Peripheral awareness (small / fast mobs)
                    if (dot > PERIPHERAL_DOT && distance < PERIPHERAL_DISTANCE_LIMIT) {
                        viewingMob = true;
                        break;
                    }
                }

                int viewingMobFlag = viewingMob ? 1 : 0;

                writer.write(
                        id + "," +
                        px + "," + py + "," + pz + "," +
                        look.x + "," + look.y + "," + look.z + "," +
                        health + "," +
                        food + "," +
                        biome + "," +
                        viewingMobFlag + "\n"
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
                    "PlayerCords_ZPos," +
                    "PlayerViewingCords_XPos," +
                    "PlayerViewingCords_YPos," +
                    "PlayerViewingCords_ZPos," +
                    "PlayerStats_Health," +
                    "PlayerStats_FoodLevel," +
                    "PlayerEnvironment_Biome," +
                    "PlayerViewingEnvironment_Mob\n"
            );
            headerWritten = true;
        }
    }
}
