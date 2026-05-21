package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.*;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WorldStateVisualizer extends Module {
    public enum PointType { NETWORK_ENTITY, BLOCK_ENTITY, ANOMALY }

    public record CachedPoint(int id, double x, double y, double z, PointType type, long lastUpdatedTimestamp, ChunkPos chunkPos) {}

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgDetection = this.settings.createGroup("Detection");

    // ── General ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> active = sgGeneral.add(new BoolSetting.Builder()
        .name("active")
        .description("Enables the visualizer.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cacheRange = sgGeneral.add(new IntSetting.Builder()
        .name("cacheRange")
        .description("Radius in blocks to cache data points.")
        .defaultValue(64)
        .min(16)
        .max(256)
        .sliderRange(16, 256)
        .build()
    );

    private final Setting<Integer> cacheRetention = sgGeneral.add(new IntSetting.Builder()
        .name("cacheRetention")
        .description("How long in milliseconds to retain cached points.")
        .defaultValue(30000)
        .min(1000)
        .max(30000)
        .sliderRange(1000, 30000)
        .build()
    );

    private final Setting<SettingColor> networkEntityColor = sgGeneral.add(new ColorSetting.Builder()
        .name("networkEntityColor")
        .description("Color for player-activity points.")
        .defaultValue(new SettingColor(0, 100, 255, 255))
        .build()
    );

    private final Setting<SettingColor> blockEntityColor = sgGeneral.add(new ColorSetting.Builder()
        .name("blockEntityColor")
        .description("Color for spawner/block-entity points.")
        .defaultValue(new SettingColor(255, 160, 0, 255))
        .build()
    );

    private final Setting<SettingColor> anomalyColor = sgGeneral.add(new ColorSetting.Builder()
        .name("anomalyColor")
        .description("Color for anomaly signals (bobber, leash, vehicle, elytra, mob-target, world-diff).")
        .defaultValue(new SettingColor(255, 0, 220, 255))
        .build()
    );

    private final Setting<SettingColor> notableChunkColor = sgGeneral.add(new ColorSetting.Builder()
        .name("notableChunkColor")
        .description("Color for notable chunk highlights.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    private final Setting<SettingColor> indicatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("indicatorColor")
        .description("Color for trace lines.")
        .defaultValue(new SettingColor(128, 0, 32, 255))
        .build()
    );

    private final Setting<Boolean> cacheAudioCues = sgGeneral.add(new BoolSetting.Builder()
        .name("cacheAudioCues")
        .description("Cache audio-cue-based positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cacheVisualCues = sgGeneral.add(new BoolSetting.Builder()
        .name("cacheVisualCues")
        .description("Cache particle-based positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("debugLogging")
        .description("Enable debug logging.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> boxScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("boxScale")
        .description("Scale of rendered point boxes.")
        .defaultValue(1.0)
        .range(0.1, 4.0)
        .sliderRange(0.1, 4.0)
        .build()
    );

    private final Setting<Boolean> renderIndicators = sgGeneral.add(new BoolSetting.Builder()
        .name("renderIndicators")
        .description("Render trace lines to cached points.")
        .defaultValue(true)
        .build()
    );

    // ── Detection toggles ─────────────────────────────────────────────────────

    private final Setting<Boolean> fishingBobberDetection = sgDetection.add(new BoolSetting.Builder()
        .name("fishingBobberDetection")
        .description("Flag fishing bobbers whose owner is not in the client entity list (orphaned bobber = hidden fisher).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> leashDetection = sgDetection.add(new BoolSetting.Builder()
        .name("leashDetection")
        .description("Flag mobs leashed by an entity not present in the client entity list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> vehiclePassengerDetection = sgDetection.add(new BoolSetting.Builder()
        .name("vehiclePassengerDetection")
        .description("Flag vehicles carrying passengers that are not in the client entity list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> elytraDetection = sgDetection.add(new BoolSetting.Builder()
        .name("elytraDetection")
        .description("Flag entities actively gliding with elytra that are not tracked as visible.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> worldStateDiffing = sgDetection.add(new BoolSetting.Builder()
        .name("worldStateDiffing")
        .description("Flag entities present in the world that never had a spawn packet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mobTargetDetection = sgDetection.add(new BoolSetting.Builder()
        .name("mobTargetDetection")
        .description("Flag mobs whose attack target is a player not in the client entity list.")
        .defaultValue(true)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    public final Map<Integer, CachedPoint> cachedPoints = new ConcurrentHashMap<>();
    public final Set<ChunkPos> notableChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> pendingChunkScans = ConcurrentHashMap.newKeySet();
    private final Set<Integer> seenSpawnerIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> seenChunkKeys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> knownEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> seenAnomalyIds = ConcurrentHashMap.newKeySet();
    private final Color tempFill = new Color();
    private final Color tempChunkFill = new Color();
    private int tickCounter = 0;
    private int anomalyScanTick = 0;
    private int graceTicks = 0;

    public WorldStateVisualizer() {
        super(AddonTemplate.CATEGORY, "world-state-visualizer", "Passively visualizes cached world state data received from the server.");
    }

    @Override
    public void onActivate() {
        graceTicks = 0;
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                knownEntityIds.add(e.getId());
            }
        }
    }

    @Override
    public void onDeactivate() {
        cachedPoints.clear();
        notableChunks.clear();
        pendingChunkScans.clear();
        seenSpawnerIds.clear();
        seenChunkKeys.clear();
        knownEntityIds.clear();
        seenAnomalyIds.clear();
        graceTicks = 0;
    }

    // ── Packet handling ───────────────────────────────────────────────────────

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!active.get() || mc.level == null) return;

        if (event.packet instanceof ClientboundAddEntityPacket packet) {
            knownEntityIds.add(packet.getId());
            if (packet.getType() == EntityType.PLAYER) {
                double x = packet.getX(), y = packet.getY(), z = packet.getZ();
                cachePoint(new CachedPoint(packet.getId(), x, y, z, PointType.NETWORK_ENTITY,
                    System.currentTimeMillis(), new ChunkPos(BlockPos.containing(x, y, z))));
            }

        } else if (event.packet instanceof ClientboundRemoveEntitiesPacket packet) {
            for (int id : packet.getEntityIds()) {
                knownEntityIds.remove(id);
            }

        } else if (event.packet instanceof ClientboundEntityPositionSyncPacket packet) {
            if (!(mc.level.getEntity(packet.id()) instanceof Player)) return;
            Vec3 pos = packet.values().position();
            updatePosition(packet.id(), pos.x, pos.y, pos.z);

        } else if (event.packet instanceof ClientboundLevelChunkWithLightPacket packet) {
            pendingChunkScans.add(new ChunkPos(packet.getX(), packet.getZ()));

        } else if (event.packet instanceof ClientboundBlockEntityDataPacket packet) {
            var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(packet.getType());
            if (key == null || !key.toString().equals("minecraft:mob_spawner")) return;
            notifyAndCacheSpawner(packet.getPos(), System.currentTimeMillis());

        } else if (event.packet instanceof ClientboundSoundPacket packet) {
            if (!cacheAudioCues.get()) return;
            if (!isNetworkEntitySound(packet.getSound().value())) return;
            double x = packet.getX(), y = packet.getY(), z = packet.getZ();
            BlockPos bp = BlockPos.containing(x, y, z);
            cachePoint(new CachedPoint(bp.hashCode(), x, y, z, PointType.NETWORK_ENTITY,
                System.currentTimeMillis(), new ChunkPos(bp)));

        } else if (event.packet instanceof ClientboundLevelParticlesPacket packet) {
            if (!cacheVisualCues.get()) return;
            PointType pointType = getPointTypeFromParticle(packet.getParticle().getType());
            if (pointType == null) return;
            double x = packet.getX(), y = packet.getY(), z = packet.getZ();
            BlockPos bp = BlockPos.containing(x, y, z);
            ChunkPos chunk = new ChunkPos(bp);
            cachePoint(new CachedPoint(bp.hashCode(), x, y, z, pointType, System.currentTimeMillis(), chunk));
            if (pointType == PointType.BLOCK_ENTITY) notableChunks.add(chunk);
        }
    }

    private void cachePoint(CachedPoint data) {
        cachedPoints.put(data.id(), data);
    }

    private void updatePosition(int id, double x, double y, double z) {
        CachedPoint existing = cachedPoints.get(id);
        if (existing == null) return;
        cachedPoints.put(id, new CachedPoint(id, x, y, z, existing.type(),
            System.currentTimeMillis(), new ChunkPos(BlockPos.containing(x, y, z))));
    }

    private boolean isNetworkEntitySound(SoundEvent sound) {
        String path = sound.location().getPath();
        return path.startsWith("entity.player.") || path.startsWith("item.armor.equip_");
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!active.get() || mc.level == null || mc.player == null) return;
        if (graceTicks < 80) graceTicks++;
        pruneCache();

        if (!pendingChunkScans.isEmpty()) {
            Set<ChunkPos> batch = new HashSet<>(pendingChunkScans);
            pendingChunkScans.clear();
            for (ChunkPos cp : batch) scanChunkForSpawners(cp);
        }

        if (++tickCounter >= 60) {
            tickCounter = 0;
            scanForSpawners();
        }

        if (++anomalyScanTick >= 20) {
            anomalyScanTick = 0;
            scanForAnomalies();
        }
    }

    // ── Anomaly scanning ──────────────────────────────────────────────────────

    private void scanForAnomalies() {
        if (mc.level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        int localId = mc.player.getId();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getId() == localId) continue;

            // Orphaned fishing bobber → hidden player is fishing nearby
            if (fishingBobberDetection.get() && entity instanceof FishingHook hook) {
                Entity owner = hook.getOwner();
                if (owner == null || !knownEntityIds.contains(owner.getId())) {
                    notifyAndCacheAnomaly(entity.getId(),
                        entity.getX(), entity.getY(), entity.getZ(),
                        "Orphaned fishing bobber (hidden fisher) at " + fmtPos(entity), now);
                }
            }

            // Mob leashed by an entity not in our list → hidden player holding lead
            if (leashDetection.get() && entity instanceof Mob mob && mob.isLeashed()) {
                Entity holder = mob.getLeashHolder();
                if (holder != null && !knownEntityIds.contains(holder.getId())) {
                    notifyAndCacheAnomaly(holder.getId(),
                        holder.getX(), holder.getY(), holder.getZ(),
                        "Leash held by unknown entity near " + fmtPos(entity), now);
                }
            }

            // Vehicle with a passenger not in our entity list → hidden passenger
            if (vehiclePassengerDetection.get() && entity.isVehicle()) {
                for (Entity passenger : entity.getPassengers()) {
                    if (!knownEntityIds.contains(passenger.getId())) {
                        notifyAndCacheAnomaly(passenger.getId(),
                            passenger.getX(), passenger.getY(), passenger.getZ(),
                            "Unknown vehicle passenger at " + fmtPos(passenger), now);
                    }
                }
            }

            // Entity actively gliding with elytra but not in our known list
            if (elytraDetection.get()
                    && entity instanceof LivingEntity living
                    && living.isFallFlying()
                    && !knownEntityIds.contains(entity.getId())) {
                notifyAndCacheAnomaly(entity.getId(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    "Elytra flight from untracked entity at " + fmtPos(entity), now);
            }

            // Entity in world without a spawn packet (after grace period)
            if (worldStateDiffing.get() && graceTicks >= 80 && !knownEntityIds.contains(entity.getId())) {
                notifyAndCacheAnomaly(entity.getId(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    "Entity appeared without spawn packet at " + fmtPos(entity), now);
                knownEntityIds.add(entity.getId()); // suppress repeat until next expiry
            }

            // Mob is targeting a player that is not in our entity list
            if (mobTargetDetection.get() && entity instanceof Mob mob) {
                LivingEntity target = mob.getTarget();
                if (target instanceof Player && !knownEntityIds.contains(target.getId())) {
                    notifyAndCacheAnomaly(target.getId(),
                        target.getX(), target.getY(), target.getZ(),
                        "Mob targeting unknown player at " + fmtPos(target), now);
                }
            }
        }
    }

    private void notifyAndCacheAnomaly(int id, double x, double y, double z, String msg, long now) {
        ChunkPos cp = new ChunkPos(BlockPos.containing(x, y, z));
        cachePoint(new CachedPoint(id, x, y, z, PointType.ANOMALY, now, cp));
        if (seenAnomalyIds.add(id)) {
            info(msg);
        }
    }

    private String fmtPos(Entity e) {
        return (int) e.getX() + ", " + (int) e.getY() + ", " + (int) e.getZ();
    }

    // ── Spawner scanning ──────────────────────────────────────────────────────

    private void scanChunkForSpawners(ChunkPos cp) {
        if (!mc.level.hasChunk(cp.x, cp.z)) return;
        LevelChunk chunk = (LevelChunk) mc.level.getChunk(cp.x, cp.z);
        long now = System.currentTimeMillis();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            if (mc.level.getBlockEntity(pos) instanceof SpawnerBlockEntity) {
                notifyAndCacheSpawner(pos, now);
            }
        }
    }

    private void notifyAndCacheSpawner(BlockPos pos, long now) {
        int id = pos.hashCode();
        ChunkPos cp = new ChunkPos(pos);
        cachePoint(new CachedPoint(id, pos.getX(), pos.getY(), pos.getZ(), PointType.BLOCK_ENTITY, now, cp));
        if (seenSpawnerIds.add(id)) {
            info("Spawner found at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
            if (seenChunkKeys.add(cp.toLong())) {
                info("Suspicious chunk at " + cp.x + ", " + cp.z);
            }
        }
    }

    private void scanForSpawners() {
        int chunkRadius = (cacheRange.get() >> 4) + 1;
        int px = mc.player.chunkPosition().x;
        int pz = mc.player.chunkPosition().z;
        for (int cx = px - chunkRadius; cx <= px + chunkRadius; cx++) {
            for (int cz = pz - chunkRadius; cz <= pz + chunkRadius; cz++) {
                scanChunkForSpawners(new ChunkPos(cx, cz));
            }
        }
    }

    // ── Cache maintenance ─────────────────────────────────────────────────────

    private void pruneCache() {
        long now = System.currentTimeMillis();
        Set<Integer> removed = new HashSet<>();
        cachedPoints.entrySet().removeIf(entry -> {
            CachedPoint point = entry.getValue();
            boolean expired = now - point.lastUpdatedTimestamp() > cacheRetention.get();
            boolean outOfRange = false;
            if (mc.player != null) {
                double dx = point.x() - mc.player.getX();
                double dy = point.y() - mc.player.getY();
                double dz = point.z() - mc.player.getZ();
                outOfRange = Math.sqrt(dx * dx + dy * dy + dz * dz) > cacheRange.get();
            }
            if (expired || outOfRange) {
                removed.add(entry.getKey());
                return true;
            }
            return false;
        });
        seenAnomalyIds.removeAll(removed);

        notableChunks.clear();
        for (CachedPoint point : cachedPoints.values()) {
            if (point.type() == PointType.BLOCK_ENTITY) notableChunks.add(point.chunkPos());
        }
        if (debugLogging.get()) {
            AddonTemplate.LOG.info("[WorldStateVisualizer] Cache size: {}", cachedPoints.size());
        }
    }

    private PointType getPointTypeFromParticle(ParticleType<?> type) {
        if (type == ParticleTypes.CRIT || type == ParticleTypes.ENCHANTED_HIT ||
            type == ParticleTypes.ENCHANT || type == ParticleTypes.ENTITY_EFFECT ||
            type == ParticleTypes.SWEEP_ATTACK || type == ParticleTypes.DAMAGE_INDICATOR) {
            return PointType.NETWORK_ENTITY;
        }
        if (type == ParticleTypes.FLAME || type == ParticleTypes.SMOKE || type == ParticleTypes.LARGE_SMOKE) {
            return PointType.BLOCK_ENTITY;
        }
        return null;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!active.get() || mc.level == null) return;

        int minY = mc.level.getMinY();
        int maxY = minY + mc.level.getHeight();
        SettingColor chunkLine = notableChunkColor.get();
        tempChunkFill.set(chunkLine.r, chunkLine.g, chunkLine.b, 18);
        for (ChunkPos chunk : notableChunks) {
            int x1 = chunk.getMinBlockX(), z1 = chunk.getMinBlockZ();
            int x2 = chunk.getMaxBlockX() + 1, z2 = chunk.getMaxBlockZ() + 1;
            event.renderer.box(x1, minY, z1, x2, maxY, z2, tempChunkFill, chunkLine, ShapeMode.Both, 0);
        }

        double half = boxScale.get() / 2.0;
        for (CachedPoint point : cachedPoints.values()) {
            SettingColor line = switch (point.type()) {
                case NETWORK_ENTITY -> networkEntityColor.get();
                case BLOCK_ENTITY   -> blockEntityColor.get();
                case ANOMALY        -> anomalyColor.get();
            };
            tempFill.set(line.r, line.g, line.b, 40); // transparent fill, full-opacity outline
            event.renderer.box(
                point.x() - half, point.y() - half, point.z() - half,
                point.x() + half, point.y() + half, point.z() + half,
                tempFill, line, ShapeMode.Both, 0
            );
        }

        if (renderIndicators.get() && mc.player != null) {
            Vec3 eye = mc.player.getEyePosition(event.tickDelta);
            double range = cacheRange.get();
            for (CachedPoint point : cachedPoints.values()) {
                double dx = point.x() - mc.player.getX();
                double dy = point.y() - mc.player.getY();
                double dz = point.z() - mc.player.getZ();
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > range) continue;
                event.renderer.line(eye.x, eye.y, eye.z, point.x(), point.y(), point.z(), indicatorColor.get());
            }
        }
    }
}
