package positionlogger_v0_0;

import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.stream.Collectors;


import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ClipContext;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;


import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;


import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;




public class PositionLogger implements ModInitializer {

    private FileWriter writer;
    private boolean headerWritten = false;

    private static final DateTimeFormatter ISO_FORMAT =
        DateTimeFormatter.ISO_INSTANT;

    // Vision parameters (human-like)
    private static final double MAX_VIEW_DISTANCE = 64.0;

    // Peripheral awareness radius
    private static final double PERIPHERAL_RADIUS = 3.0;
    private static final double PERIPHERAL_DISTANCE_LIMIT = 8.0;

    private boolean wasViewingMobLastTick = false;
    private String lastClosestViewingMobType = "none";
    private String lastAllViewingMobs = "none";

    // --- Vision-ish block sampling (first-hit surface only) ---
    private static final double BLOCK_SAMPLE_MAX_DISTANCE = 64.0;

    private static final int SAMPLE_W = 128;
    private static final int SAMPLE_H = 64;

    // Approximate FOV. Tune if desired.
    private static final double FOV_DEG_H = 90.0;
    private static final double FOV_DEG_V = 90.0;

    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

    // Precomputed normalized screen coords in [-1, 1]
    private final double[] sampleU = new double[SAMPLE_W];
    private final double[] sampleV = new double[SAMPLE_H];
    private boolean blockSamplerInited = false;


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

    private void initBlockSamplerIfNeeded() {
        if (blockSamplerInited) return;

        for (int x = 0; x < SAMPLE_W; x++) {
            double nx = (x + 0.5) / (double) SAMPLE_W; // 0..1
            sampleU[x] = nx * 2.0 - 1.0;               // -1..1
        }
        for (int y = 0; y < SAMPLE_H; y++) {
            double ny = (y + 0.5) / (double) SAMPLE_H; // 0..1
            sampleV[y] = 1.0 - ny * 2.0;               // +1..-1 (top->bottom)
        }

        blockSamplerInited = true;
    }
    private Map<String, Integer> sampleVisibleBlocks(ServerPlayer player) {

        initBlockSamplerIfNeeded();

        Map<String, java.util.HashSet<BlockPos>> seen = new HashMap<>();

        Vec3 eye = player.getEyePosition();
        Vec3 forward = player.getLookAngle().normalize();

        Vec3 right = forward.cross(WORLD_UP);
        if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
        else right = right.normalize();
        Vec3 up = right.cross(forward).normalize();

        double tanH = Math.tan(Math.toRadians(FOV_DEG_H * 0.5));
        double tanV = Math.tan(Math.toRadians(FOV_DEG_V * 0.5));

        for (int yi = 0; yi < SAMPLE_H; yi++) {
            double v = sampleV[yi] * tanV;

            for (int xi = 0; xi < SAMPLE_W; xi++) {
                double u = sampleU[xi] * tanH;

                Vec3 dir = forward.add(right.scale(u)).add(up.scale(v)).normalize();
                Vec3 end = eye.add(dir.scale(BLOCK_SAMPLE_MAX_DISTANCE));

                HitResult hit = player.level().clip(new ClipContext(
                        eye, end,
                        ClipContext.Block.OUTLINE, // keep OUTLINE so plants can be hit
                        ClipContext.Fluid.ANY,
                        player
                ));

                if (hit.getType() != HitResult.Type.BLOCK) continue;

                BlockPos hitPos = ((BlockHitResult) hit).getBlockPos();
                BlockState bs = player.level().getBlockState(hitPos);

                String blockKey = BuiltInRegistries.BLOCK
                        .getKey(bs.getBlock())
                        .getPath();

                seen.computeIfAbsent(blockKey, k -> new java.util.HashSet<>()).add(hitPos);
            }
        }

        Map<String, Integer> uniqueCounts = new HashMap<>();
        for (Map.Entry<String, java.util.HashSet<BlockPos>> e : seen.entrySet()) {
            uniqueCounts.put(e.getKey(), e.getValue().size());
        }
        return uniqueCounts;
    }


    private void onServerTick(MinecraftServer server) {
        try {
            if (writer == null) {
                Path logPath = FabricLoader.getInstance()
                        .getGameDir()
                        .resolve("loggger.csv");
                writer = new FileWriter(logPath.toFile(), true);
                writeHeaderIfNeeded();
            }

            long tick = server.overworld().getGameTime();
            String timestamp = ISO_FORMAT.format(Instant.now());

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String playerName = player.getName().getString();
                String id = playerName + "_" + tick + "_" + timestamp;


                // Player position
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();

                // View information
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getLookAngle();
                boolean outsideObservable = false;

                // ---------------- HOTBAR (0–8) ----------------
                String[] hotbar = new String[9];

                for (int i = 0; i < 9; i++) {
                    hotbar[i] =
                            formatSlot(player.getInventory().getItem(i));
                }

                // ---------------- MAIN INVENTORY (0–35) ----------------
                // Includes hotbar again intentionally (full 36 slots)
                String[] mainInventory = new String[36];

                for (int i = 0; i < 36; i++) {
                    mainInventory[i] =
                            formatSlot(player.getInventory().getItem(i));
                }

                // ---------------- ARMOR ----------------
                String[] armorInventory = new String[4];

                armorInventory[0] = formatSlot(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
                armorInventory[1] = formatSlot(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
                armorInventory[2] = formatSlot(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS));
                armorInventory[3] = formatSlot(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET));

                // ---------------- SHIELD / OFFHAND ----------------
                String shieldSlot =
                        formatSlot(player.getOffhandItem());


                // --- Visible block sampling (first-hit surface only) ---
                Map<String, Integer> visibleBlockCounts = sampleVisibleBlocks(player);
                String visibleBlocks = blockCountsToString(visibleBlockCounts);
                String visibleBlocksCsv = "\"" + visibleBlocks.replace("\"", "\"\"") + "\"";


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
                double food = player.getFoodData().getFoodLevel();
                double oxygen = player.getAirSupply(); 

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

                double visibleDistance = blockHit.getType() == HitResult.Type.MISS
                ? MAX_VIEW_DISTANCE
                : blockHit.getLocation().distanceTo(eye);


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
                                    .getPath();}
                   
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

                        double FOV_DOT = Math.cos(Math.toRadians(45.0)) ; // ~90°
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
                        oxygen + "," +
                        biome + "," +
                        outsideObservableFlag + "," +
                        nightFlag + "," +
                        blockHighlightedFlag + "," +
                        highlightedBlock + "," +
                        visibleBlocksCsv + "," + 
                        viewingMobFlag + "," + 
                        closestViewingMobType + "," +
                        allViewingMobs + "," 
                    );
                        // --- Hotbar ---
                        for (int i = 0; i < 9; i++) {
                            writer.write(hotbar[i] + ",");
                        }

                        // --- Main Inventory ---
                        for (int i = 0; i < 36; i++) {
                            writer.write(mainInventory[i] + ",");
                        }

                        // --- Armor ---
                        for (int i = 0; i < 4; i++) {
                            writer.write(armorInventory[i] + ",");
                        }

                        // --- Shield ---
                        writer.write(shieldSlot + "\n");
            }

            writer.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String blockCountsToString(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "none";

        return counts.entrySet()
                .stream()
                .sorted(Comparator
                        .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey)
                )
                .map(e -> e.getKey() + " : " + e.getValue())
                .collect(Collectors.joining("; "));
    }

    private static String formatSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "none";

        String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int count = stack.getCount();

        if (count <= 1) return itemName;
        return itemName + " : " + count;
    }


    private static double degToRad(double deg) {
        return deg * Math.PI / 180.0;
    }

    private static Vec3 dirFromYawPitch(double yawDeg, double pitchDeg) {
        double yaw = degToRad(yawDeg);
        double pitch = degToRad(pitchDeg);

        double x = -Math.sin(yaw) * Math.cos(pitch);
        double y = -Math.sin(pitch);
        double z =  Math.cos(yaw) * Math.cos(pitch);

        return new Vec3(x, y, z);
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
                    "PlayerStats_OxygenLevel," +
                    "PlayerEnvironment_Biome," +
                    "PlayerViewingEnvironment_OutsideObservableFlag," +
                    "PlayerViewingEnvironment_NightFlag," +
                    "PlayerViewingEnvironment_BlockHighlightedFlag," +
                    "PlayerViewingEnvironment_HighlightedBlock," +
                    "PlayerViewingEnvironment_VisibleBlocks," +
                    "PlayerViewingEnvironment_MobFlag," +
                    "PlayerViewingEnvironment_ClosestViewingMobType," +
                    "PlayerViewingEnvironment_AllViewingMobs,"  
                );
                    // Hotbar
                    for (int i = 1; i <= 9; i++) {
                        writer.write("PlayerInventory_Item" + i + "Hotbar,");
                    }

                    // Main Inventory
                    for (int i = 1; i <= 36; i++) {
                        writer.write("PlayerInventory_MainInventory_Item" + i + ",");
                    }

                    // Armor
                    for (int i = 1; i <= 4; i++) {
                        writer.write("PlayerInventory_Item" + i + "Armor,");
                    }

                    // Shield
                    writer.write("PlayerInventory_ShieldSlot\n");


            headerWritten = true;
        }
    }
}
