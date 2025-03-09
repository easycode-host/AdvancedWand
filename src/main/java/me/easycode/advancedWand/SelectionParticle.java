package me.easycode.advancedWand;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SelectionParticle {
    private static JavaPlugin plugin;
    private static final ConcurrentHashMap<UUID, BukkitTask> particleTasks = new ConcurrentHashMap<>();
    private static final Particle.DustOptions GREEN_DUST = new Particle.DustOptions(Color.fromRGB(0, 255, 0), 1.0F);

    public SelectionParticle(JavaPlugin plugin) {
        SelectionParticle.plugin = plugin;
    }

    public static void showSelection(Player player, Location pos1, Location pos2) {
        stopDisplay(player);

        particleTasks.put(player.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stopDisplay(player);
                return;
            }

            Cuboid cuboid = new Cuboid(pos1, pos2);
            drawEdges(player, cuboid);
        }, 0L, 5L));
    }

    private static void drawEdges(Player player, Cuboid cuboid) {
        World world = cuboid.getWorld();
        double[] bounds = cuboid.getBounds();
        double minX = bounds[0];
        double maxX = bounds[1];
        double minY = bounds[2];
        double maxY = bounds[3];
        double minZ = bounds[4];
        double maxZ = bounds[5];

        // X方向边线（底面和顶面）
        drawLine(world, minX, minY, minZ, maxX, minY, minZ); // 底面X边
        drawLine(world, minX, minY, maxZ, maxX, minY, maxZ); // 底面另一侧X边
        drawLine(world, minX, maxY, minZ, maxX, maxY, minZ); // 顶面X边
        drawLine(world, minX, maxY, maxZ, maxX, maxY, maxZ); // 顶面另一侧X边

        // Z方向边线（底面和顶面）
        drawLine(world, minX, minY, minZ, minX, minY, maxZ); // 底面Z边
        drawLine(world, maxX, minY, minZ, maxX, minY, maxZ); // 底面另一侧Z边
        drawLine(world, minX, maxY, minZ, minX, maxY, maxZ); // 顶面Z边
        drawLine(world, maxX, maxY, minZ, maxX, maxY, maxZ); // 顶面另一侧Z边

        // Y方向边线（四个垂直边）
        drawLine(world, minX, minY, minZ, minX, maxY, minZ); // 前左垂直边
        drawLine(world, maxX, minY, minZ, maxX, maxY, minZ); // 前右垂直边
        drawLine(world, minX, minY, maxZ, minX, maxY, maxZ); // 后左垂直边
        drawLine(world, maxX, minY, maxZ, maxX, maxY, maxZ); // 后右垂直边
    }

    private static void drawLine(World world, double x1, double y1, double z1, double x2, double y2, double z2) {
        Vector direction = new Vector(x2 - x1, y2 - y1, z2 - z1);
        double length = direction.length();
        direction.normalize().multiply(0.5);

        for (double d = 0; d < length; d += direction.length()) {
            Location point = new Location(world,
                    x1 + direction.getX() * d,
                    y1 + direction.getY() * d,
                    z1 + direction.getZ() * d
            ).add(0.5, 0.5, 0.5);
            world.spawnParticle(Particle.REDSTONE, point, 1, GREEN_DUST);
        }
    }

    public static void stopDisplay(Player player) {
        BukkitTask task = particleTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    public void cleanupAll() {
        particleTasks.values().forEach(BukkitTask::cancel);
        particleTasks.clear();
    }

    // 立方体边界计算工具类
    private static class Cuboid {
        private final double minX, maxX;
        private final double minY, maxY;
        private final double minZ, maxZ;
        private final World world;

        public Cuboid(Location loc1, Location loc2) {
            this.world = loc1.getWorld();
            this.minX = Math.min(loc1.getX(), loc2.getX());
            this.maxX = Math.max(loc1.getX(), loc2.getX());
            this.minY = Math.min(loc1.getY(), loc2.getY());
            this.maxY = Math.max(loc1.getY(), loc2.getY());
            this.minZ = Math.min(loc1.getZ(), loc2.getZ());
            this.maxZ = Math.max(loc1.getZ(), loc2.getZ());
        }

        public double[] getBounds() {
            return new double[]{minX, maxX, minY, maxY, minZ, maxZ};
        }

        public World getWorld() {
            return world;
        }
    }
}