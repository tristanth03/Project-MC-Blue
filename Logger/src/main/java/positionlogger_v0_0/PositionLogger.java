package positionlogger_v0_0;

import java.util.Map;
import java.util.HashMap;



import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;



import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;

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

    private boolean wasViewingMobLastTick = false;
    private String lastClosestViewingMobType = "none";
    private String lastAllViewingMobs = "none";


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
                boolean outsideObservable = false;

                Vec3[] directions = new Vec3[] {
                        look,
                        look.add(0.2, 0, 0).normalize(),
                        look.add(-0.2, 0, 0).normalize(),
                        look.add(0, 0.2, 0).normalize(),
                        look.add(0, -0.2, 0).normalize()
                };     

                for (Vec3 dir : directions) {

                    Vec3 rayEndSample = eye.add(dir.scale(MAX_VIEW_DISTANCE));

                    HitResult hit = player.level().clip(
                            new net.minecraft.world.level.ClipContext(
                                    eye,
                                    rayEndSample,
                                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                                    player
                            )
                    );

                    if (hit.getType() == HitResult.Type.MISS) {
                        outsideObservable = true;
                        break;
                    }

                    // Check if the hit position receives sky light
                    BlockPos hitPos = BlockPos.containing(hit.getLocation());

                    int skyLight = player.level().getBrightness(
                            LightLayer.SKY,
                            hitPos
                    );

                    if (skyLight > 0) {
                        outsideObservable = true;
                        break;
                    }

                }

                int outsideObservableFlag = outsideObservable ? 1 : 0; 
                // --- NightFlag logic ---
                String nightFlag = "none";

                boolean hasSky = player.level().dimensionType().hasSkyLight();

                if (outsideObservable && hasSky) {

                    long dayTime = player.level().getDayTime() % 24000L;

                    // Vanilla night window: 13000–23000
                    boolean isNight = dayTime >= 13000L && dayTime <= 23000L;

                    nightFlag = isNight ? "1" : "0";
                }
         

                // Player stats
                double health = player.getHealth();
                int food = player.getFoodData().getFoodLevel();

                // Biome (1.15-safe, player-perceivable)
                String biomeRaw = player.level()
                        .getBiome(player.blockPosition())
                        .toString();

                // Find last ":" (before biome name)
                int colon = biomeRaw.lastIndexOf(":");

                // Find closing bracket after biome name
                int endBracket = biomeRaw.indexOf("]", colon);

                // Extract biome name
                String biome = biomeRaw.substring(colon + 1, endBracket);

                // Determine visible distance via block raycast
                HitResult blockHit = player.pick(MAX_VIEW_DISTANCE, 0.0f, false);

                // --- BlockHighlightedFlag ---
                int blockHighlightedFlag =
                        (blockHit.getType() == HitResult.Type.BLOCK) ? 1 : 0;

                // --- HighlightedBlock ---
                String highlightedBlock = "none";

                if (blockHit.getType() == HitResult.Type.BLOCK) {

                    BlockPos hitPos = BlockPos.containing(blockHit.getLocation());

                    highlightedBlock =
                            BuiltInRegistries.BLOCK
                                    .getKey(player.level().getBlockState(hitPos).getBlock())
                                    .getPath();
}


                double visibleDistance = blockHit.getType() == HitResult.Type.MISS
                        ? MAX_VIEW_DISTANCE
                        : blockHit.getLocation().distanceTo(eye);
      
                // Ray end point (limited by visible distance)
                Vec3 rayEnd = eye.add(look.scale(visibleDistance));

                List<Entity> candidates = player.level().getEntities(
                        player,
                        player.getBoundingBox()
                                .expandTowards(look.scale(visibleDistance))
                                .inflate(PERIPHERAL_RADIUS),
                        e -> e instanceof Mob
                );

                boolean detectedThisTick = false;

                Mob closestViewingMob = null;
                double closestDistance = Double.MAX_VALUE;
                Map<String, Integer> viewingMobCounts = new HashMap<>();
                String allViewingMobs = "none";


    
                for (Entity entity : candidates) {

                    Vec3 toEntityCenter = entity.getBoundingBox()
                            .getCenter()
                            .subtract(eye);

                    double distance = toEntityCenter.length();
                    if (distance > visibleDistance) continue;

                    boolean isVisible = false;

                    if (entity.getBoundingBox().inflate(0.1).clip(eye, rayEnd).isPresent()) {
                        isVisible = true;
                    } else {
                        Vec3 dir = toEntityCenter.normalize();
                        double dot = look.dot(dir);

                        double FOV_DOT = 0.0; // ~180°
                        if (dot > FOV_DOT) {
                            isVisible = true;
                        }
                    }
                    if (isVisible) {
                        detectedThisTick = true;

                        // Get mob type name
                        String mobType =
                                BuiltInRegistries.ENTITY_TYPE
                                        .getKey(entity.getType())
                                        .getPath();

                        // Count occurrences
                        viewingMobCounts.put(
                                mobType,
                                viewingMobCounts.getOrDefault(mobType, 0) + 1
                        );

                        // Track closest
                        if (distance < closestDistance) {
                            closestDistance = distance;
                            closestViewingMob = (Mob) entity;
                        }
                    }

                }


                // 1-tick smoothing
                boolean viewingMob = detectedThisTick || wasViewingMobLastTick;
                wasViewingMobLastTick = detectedThisTick;

                int viewingMobFlag = viewingMob ? 1 : 0;

                String closestViewingMobType = "none";

                if (closestViewingMob != null) {
                    closestViewingMobType =
                            BuiltInRegistries.ENTITY_TYPE
                                    .getKey(closestViewingMob.getType())
                                    .getPath();
                }


                if (!viewingMobCounts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();

                    for (Map.Entry<String, Integer> entry : viewingMobCounts.entrySet()) {
                        sb.append(entry.getKey())
                        .append(" : ")
                        .append(entry.getValue())
                        .append("; ");
                    }

                    // Remove trailing comma + space
                    sb.setLength(sb.length() - 2);

                    allViewingMobs = sb.toString();
                }
                if (detectedThisTick) {
                    lastClosestViewingMobType = closestViewingMobType;
                    lastAllViewingMobs = allViewingMobs;
                } else if (viewingMob) {
                    closestViewingMobType = lastClosestViewingMobType;
                    allViewingMobs = lastAllViewingMobs;
                }

                writer.write(
                        id + "," +
                        px + "," + py + "," + pz + "," +
                        look.x + "," + look.y + "," + look.z + "," +
                        health + "," +
                        food + "," +
                        biome + "," +
                        outsideObservableFlag + "," +
                        nightFlag + "," +
                        blockHighlightedFlag + "," +
                        highlightedBlock + "," +
                        viewingMobFlag + "," + 
                        closestViewingMobType + "," +
                        allViewingMobs + "\n"
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
                    "PlayerViewingEnvironment_OutsideObservableFlag," +
                    "PlayerViewingEnvironment_NightFlag," +
                    "PlayerViewingEnvironment_BlockHighlightedFlag," +
                    "PlayerViewingEnvironment_HighlightedBlock," +
                    "PlayerViewingEnvironment_MobFlag," +
                    "PlayerViewingEnvironment_ClosestViewingMobType," +
                    "PlayerViewingEnvironment_AllViewingMobs\n"

            );
            headerWritten = true;
        }
    }
}
