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
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WorldStateVisualizer extends Module {
    public enum PointType { NETWORK_ENTITY, BLOCK_ENTITY }

    public record CachedPoint(int id, double x, double y, double z, PointType type, long lastUpdatedTimestamp, ChunkPos chunkPos) {}

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

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
        .description("Color for network entity points.")
        .defaultValue(new SettingColor(0, 100, 255, 255))
        .build()
    );

    private final Setting<SettingColor> blockEntityColor = sgGeneral.add(new ColorSetting.Builder()
        .name("blockEntityColor")
        .description("Color for block entity points.")
        .defaultValue(new SettingColor(255, 160, 0, 255))
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
        .description("Color for indicators.")
        .defaultValue(new SettingColor(128, 0, 32, 255))
        .build()
    );

    private final Setting<Boolean> cacheAudioCues = sgGeneral.add(new BoolSetting.Builder()
        .name("cacheAudioCues")
        .description("Play audio cues when cache updates.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cacheVisualCues = sgGeneral.add(new BoolSetting.Builder()
        .name("cacheVisualCues")
        .description("Show visual cues when cache updates.")
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
        .description("Scale of rendered boxes.")
        .defaultValue(1.0)
        .range(0.1, 4.0)
        .sliderRange(0.1, 4.0)
        .build()
    );

    private final Setting<Boolean> renderIndicators = sgGeneral.add(new BoolSetting.Builder()
        .name("renderIndicators")
        .description("Render indicators on screen.")
        .defaultValue(true)
        .build()
    );

    public final Map<Integer, CachedPoint> cachedPoints = new ConcurrentHashMap<>();
    public final Set<ChunkPos> notableChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> pendingChunkScans = ConcurrentHashMap.newKeySet();
    private final Set<Integer> seenSpawnerIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> seenChunkKeys = ConcurrentHashMap.newKeySet();
    private final Color tempSide = new Color();
    private final Color tempChunkFill = new Color();
    private int tickCounter = 0;

    public WorldStateVisualizer() {
        super(AddonTemplate.CATEGORY, "world-state-visualizer", "Passively visualizes cached world state data received from the server.");
    }

    @Override
    public void onActivate() {}

    @Override
    public void onDeactivate() {
        cachedPoints.clear();
        notableChunks.clear();
        pendingChunkScans.clear();
        seenSpawnerIds.clear();
        seenChunkKeys.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!active.get() || mc.level == null) return;

        if (event.packet instanceof ClientboundAddEntityPacket packet) {
            if (packet.getType() != EntityType.PLAYER) return;
            double x = packet.getX(), y = packet.getY(), z = packet.getZ();
            ChunkPos chunk = new ChunkPos(BlockPos.containing(x, y, z));
            cachePoint(new CachedPoint(packet.getId(), x, y, z, PointType.NETWORK_ENTITY, System.currentTimeMillis(), chunk));

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
            ChunkPos chunk = new ChunkPos(BlockPos.containing(x, y, z));
            cachePoint(new CachedPoint(BlockPos.containing(x, y, z).hashCode(), x, y, z, PointType.NETWORK_ENTITY, System.currentTimeMillis(), chunk));

        } else if (event.packet instanceof ClientboundLevelParticlesPacket packet) {
            if (!cacheVisualCues.get()) return;
            PointType pointType = getPointTypeFromParticle(packet.getParticle().getType());
            if (pointType == null) return;
            double x = packet.getX(), y = packet.getY(), z = packet.getZ();
            BlockPos blockPos = BlockPos.containing(x, y, z);
            ChunkPos chunk = new ChunkPos(blockPos);
            cachePoint(new CachedPoint(blockPos.hashCode(), x, y, z, pointType, System.currentTimeMillis(), chunk));
            if (pointType == PointType.BLOCK_ENTITY) notableChunks.add(chunk);
        }
    }

    private void cachePoint(CachedPoint data) {
        cachedPoints.put(data.id(), data);
    }

    private void updatePosition(int id, double x, double y, double z) {
        CachedPoint existing = cachedPoints.get(id);
        if (existing == null) return;
        cachedPoints.put(id, new CachedPoint(id, x, y, z, existing.type(), System.currentTimeMillis(), new ChunkPos(BlockPos.containing(x, y, z))));
    }

    private boolean isNetworkEntitySound(SoundEvent sound) {
        String path = sound.location().getPath();
        return path.startsWith("entity.player.") || path.startsWith("item.armor.equip_");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!active.get() || mc.level == null || mc.player == null) return;
        pruneCache();
        if (!pendingChunkScans.isEmpty()) {
            Set<ChunkPos> batch = new java.util.HashSet<>(pendingChunkScans);
            pendingChunkScans.clear();
            for (ChunkPos cp : batch) scanChunkForSpawners(cp);
        }
        if (++tickCounter >= 60) {
            tickCounter = 0;
            scanForSpawners();
        }
    }

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
        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
            for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
                scanChunkForSpawners(new ChunkPos(cx, cz));
            }
        }
    }

    private void pruneCache() {
        long now = System.currentTimeMillis();
        cachedPoints.entrySet().removeIf(entry -> {
            CachedPoint point = entry.getValue();
            if (now - point.lastUpdatedTimestamp() > cacheRetention.get()) return true;
            if (mc.player != null) {
                double dx = point.x() - mc.player.getX();
                double dy = point.y() - mc.player.getY();
                double dz = point.z() - mc.player.getZ();
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > cacheRange.get()) return true;
            }
            return false;
        });
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

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!active.get() || mc.level == null) return;

        int minY = mc.level.getMinY();
        int maxY = minY + mc.level.getHeight();
        SettingColor chunkLine = notableChunkColor.get();
        tempChunkFill.set(chunkLine.r, chunkLine.g, chunkLine.b, 18);
        for (ChunkPos chunk : notableChunks) {
            int x1 = chunk.getMinBlockX();
            int z1 = chunk.getMinBlockZ();
            int x2 = chunk.getMaxBlockX() + 1;
            int z2 = chunk.getMaxBlockZ() + 1;
            event.renderer.box(x1, minY, z1, x2, maxY, z2, tempChunkFill, chunkLine, ShapeMode.Both, 0);
        }

        for (CachedPoint point : cachedPoints.values()) {
            SettingColor src = point.type() == PointType.NETWORK_ENTITY ? networkEntityColor.get() : blockEntityColor.get();
            tempSide.set(src.r, src.g, src.b, src.a);
            double half = boxScale.get() / 2.0;
            event.renderer.box(
                point.x() - half, point.y() - half, point.z() - half,
                point.x() + half, point.y() + half, point.z() + half,
                tempSide, src, ShapeMode.Both, 0
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
