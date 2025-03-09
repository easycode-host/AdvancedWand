package me.easycode.advancedWand;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

import me.easycode.advancedWand.SelectionParticle;

public class AdvancedWand extends JavaPlugin implements Listener {

    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Deque<List<BlockRecord>>> history = new HashMap<>();
    private final Map<UUID, BuildMode> playerModes = new HashMap<>();
    private final Map<String, List<BlockVector>> blueprints = new HashMap<>();

    private final int MAX_HISTORY = 5;
    private final int MAX_BLUEPRINT_SIZE = 1000;

    enum BuildMode {
        SINGLE("单点"),
        LINE("直线"),
        PLANE("平面"),
        CUBE("立方体"),
        CYLINDER("圆柱体"),
        SPHERE("球体");

        private final String displayName;

        BuildMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    @Override
    public void onEnable() {
        createWandItem();
        getServer().getPluginManager().registerEvents(this, this);
    }
    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        return meta.hasDisplayName() &&
                meta.getDisplayName().equals(ChatColor.GOLD + "高级建筑法杖") &&
                meta.hasLore() &&
                meta.getLore().contains(ChatColor.YELLOW + "左键选择起点 | 右键选择终点") &&
                meta.hasEnchant(Enchantment.DURABILITY);
    }


    private void undoAction(Player player, String[] args) {
        int undoCount = 1;
        try {
            if (args.length > 1) undoCount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "无效的撤销次数");
            return;
        }

        Deque<List<BlockRecord>> historyStack = history.getOrDefault(player.getUniqueId(), new ArrayDeque<>());
        int totalUndone = 0;

        for (int i = 0; i < undoCount && !historyStack.isEmpty(); i++) {
            List<BlockRecord> records = historyStack.removeLast();
            records.forEach(BlockRecord::restore);
            totalUndone += records.size();
        }

        if (totalUndone > 0) {
            player.sendMessage(ChatColor.GREEN + "成功撤销 " + totalUndone + " 个方块的修改 (" + undoCount + " 步操作)");
            playEffect(player.getLocation(), Color.PURPLE);
        } else {
            player.sendMessage(ChatColor.RED + "没有可撤销的操作记录");
        }
    }

    private void giveWand(Player player) {
        if (!player.hasPermission("advancedwand.admin")) {
            player.sendMessage(ChatColor.RED + "你没有权限获得这个物品");
            return;
        }

        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "高级建筑法杖");
        meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "左键选择起点 | 右键选择终点",
                ChatColor.YELLOW + "Shift+右键切换模式",
                ChatColor.DARK_GRAY + "当前模式：基础立方体"
        ));
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        wand.setItemMeta(meta);

        HashMap<Integer, ItemStack> result = player.getInventory().addItem(wand);
        if (!result.isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), wand);
            player.sendMessage(ChatColor.GOLD + "法杖已掉落在地面");
        } else {
            player.sendMessage(ChatColor.GREEN + "已获得高级建筑法杖");
        }
    }

    private void buildPlane(Player player, Material material, int radius) {
        Location[] points = validateSelection(player);
        if (points == null) return;

        BuildMode mode = playerModes.getOrDefault(player.getUniqueId(), BuildMode.CUBE);
        List<BlockRecord> changes = new ArrayList<>();
        World world = points[0].getWorld();

        int minX = Math.min(points[0].getBlockX(), points[1].getBlockX());
        int maxX = Math.max(points[0].getBlockX(), points[1].getBlockX());
        int minZ = Math.min(points[0].getBlockZ(), points[1].getBlockZ());
        int maxZ = Math.max(points[0].getBlockZ(), points[1].getBlockZ());
        int yLevel = points[0].getBlockY();

        switch (mode) {
            case PLANE:
                for (int x = minX - radius; x <= maxX + radius; x++) {
                    for (int z = minZ - radius; z <= maxZ + radius; z++) {
                        updateBlock(world.getBlockAt(x, yLevel, z), material, changes);
                    }
                }
                break;
            default:
                // 处理其他平面方向
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = yLevel - radius; y <= yLevel + radius; y++) {
                            updateBlock(world.getBlockAt(x, y, z), material, changes);
                        }
                    }
                }
        }

        finalizeBuild(player, changes);
    }

    private Location validateSinglePoint(Player player) {
        UUID uuid = player.getUniqueId();
        Location[] points = selections.get(uuid);

        if (points == null || points[0] == null) {
            player.sendMessage(ChatColor.RED + "请先选择主坐标点");
            return null;
        }

        if (!player.getWorld().equals(points[0].getWorld())) {
            player.sendMessage(ChatColor.RED + "坐标点不在当前世界");
            return null;
        }

        if (points[0].distance(player.getLocation()) > 30) {
            player.sendMessage(ChatColor.RED + "距离坐标点过远（最大30格）");
            return null;
        }

        return points[0].clone();
    }

    private List<Block> getSelectedBlocks(Location[] points) {
        List<Block> blocks = new ArrayList<>();
        World world = points[0].getWorld();

        int minX = Math.min(points[0].getBlockX(), points[1].getBlockX());
        int minY = Math.min(points[0].getBlockY(), points[1].getBlockY());
        int minZ = Math.min(points[0].getBlockZ(), points[1].getBlockZ());
        int maxX = Math.max(points[0].getBlockX(), points[1].getBlockX());
        int maxY = Math.max(points[0].getBlockY(), points[1].getBlockY());
        int maxZ = Math.max(points[0].getBlockZ(), points[1].getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getChunk().isLoaded()) continue;
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }
    private void createWandItem() {
        ItemStack wandItem = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wandItem.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "高级建筑法杖");
        meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "左键选择起点 | 右键选择终点",
                ChatColor.YELLOW + "Shift+右键切换模式",
                ChatColor.DARK_GRAY + "当前模式：基础立方体"
        ));
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        wandItem.setItemMeta(meta);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isWand(item) || !player.hasPermission("advancedwand.use")) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            handleSelection(player, event.getClickedBlock().getLocation(), true);
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (player.isSneaking()) {
                cycleBuildMode(player);
            } else {
                handleSelection(player, event.getClickedBlock().getLocation(), false);
            }
            event.setCancelled(true);
        }
    }

    private void handleSelection(Player player, Location loc, boolean isPrimary) {
        UUID uuid = player.getUniqueId();
        Location[] points = selections.getOrDefault(uuid, new Location[2]);

        points[isPrimary ? 0 : 1] = loc;
        selections.put(uuid, points);

        String message = ChatColor.GREEN + (isPrimary ? "主" : "副") + "选择点已设置 "
                + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
        player.sendMessage(message);

        // 当两个点都设置时显示粒子
        if (points[0] != null && points[1] != null) {
            SelectionParticle.showSelection(player, points[0], points[1]);
        } else {
            SelectionParticle.stopDisplay(player);
        }

        playEffect(loc, Color.LIME);
    }

    private void cycleBuildMode(Player player) {
        BuildMode current = playerModes.getOrDefault(player.getUniqueId(), BuildMode.CUBE);
        BuildMode next = BuildMode.values()[(current.ordinal() + 1) % BuildMode.values().length];

        playerModes.put(player.getUniqueId(), next);
        updateWandLore(player, next);
        player.sendMessage(ChatColor.GOLD + "切换到模式: " + next.getDisplayName());
    }

    private void updateWandLore(Player player, BuildMode mode) {
        List<String> lore = new ArrayList<>(Arrays.asList(
                ChatColor.YELLOW + "左键选择起点 | 右键选择终点",
                ChatColor.YELLOW + "Shift+右键切换模式"
        ));
        lore.add(ChatColor.DARK_GRAY + "当前模式：" + mode.getDisplayName());

        if (mode == BuildMode.CYLINDER) {
            lore.add(ChatColor.GRAY + "用法：/awand build <材料> <半径> <高度>");
        }

        ItemMeta meta = player.getInventory().getItemInMainHand().getItemMeta();
        meta.setLore(lore);
        player.getInventory().getItemInMainHand().setItemMeta(meta);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (cmd.getName().equalsIgnoreCase("awand")) {
            if (args.length == 0) return false;

            switch (args[0].toLowerCase()) {
                case "give":
                    giveWand(player);
                    return true;
                case "build":
                    handleBuild(player, args);
                    return true;
                case "undo":
                    undoAction(player, args);
                    return true;
                case "save":
                    saveBlueprint(player, args);
                    return true;
                case "load":
                    loadBlueprint(player, args);
                    return true;
            }
        }
        return false;
    }

    private void handleBuild(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "用法: /awand build <方块> [半径]");
            return;
        }

        Material material = Material.matchMaterial(args[1]);
        if (material == null || !material.isBlock()) {
            player.sendMessage(ChatColor.RED + "无效的方块类型");
            return;
        }

        BuildMode mode = playerModes.getOrDefault(player.getUniqueId(), BuildMode.CUBE);

        int radius = 1;
        int height = 1;

        try {
            if (args.length >= 3) {
                radius = Integer.parseInt(args[2]);
                if (mode == BuildMode.CYLINDER && args.length >= 4) {
                    height = Integer.parseInt(args[3]);
                }
            }
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "无效的半径或高度值");
            return;
        }

        switch (mode) {
            case CUBE:
                buildCube(player, material);
                break;
            case LINE:
                buildLine(player, material);
                break;
            case PLANE:
                buildPlane(player, material, radius);
                break;
            case CYLINDER:
                buildCylinder(player, material, radius, height);
                break;
            case SPHERE:
                buildSphere(player, material, radius);
                break;
        }
    }

    private void buildCube(Player player, Material material) {
        Location[] points = validateSelection(player);
        if (points == null) return;

        List<BlockRecord> changes = new ArrayList<>();
        World world = points[0].getWorld();

        for (int x = Math.min(points[0].getBlockX(), points[1].getBlockX());
             x <= Math.max(points[0].getBlockX(), points[1].getBlockX()); x++) {
            for (int y = Math.min(points[0].getBlockY(), points[1].getBlockY());
                 y <= Math.max(points[0].getBlockY(), points[1].getBlockY()); y++) {
                for (int z = Math.min(points[0].getBlockZ(), points[1].getBlockZ());
                     z <= Math.max(points[0].getBlockZ(), points[1].getBlockZ()); z++) {
                    updateBlock(world.getBlockAt(x, y, z), material, changes);
                }
            }
        }
        finalizeBuild(player, changes);
    }

    private void buildLine(Player player, Material material) {
        Location[] points = validateSelection(player);
        if (points == null) return;

        List<BlockRecord> changes = new ArrayList<>();
        Vector direction = points[1].toVector().subtract(points[0].toVector());
        int length = (int) direction.length();
        direction.normalize();

        for (int i = 0; i <= length; i++) {
            Vector point = points[0].toVector().add(direction.multiply(i));
            Block block = points[0].getWorld().getBlockAt(
                    point.getBlockX(), point.getBlockY(), point.getBlockZ());
            updateBlock(block, material, changes);
        }
        finalizeBuild(player, changes);
    }

    private void buildCylinder(Player player, Material material, int radius, int height) {
        Location center = validateSinglePoint(player);
        if (center == null) return;

        List<BlockRecord> changes = new ArrayList<>();
        World world = center.getWorld();
        int xCenter = center.getBlockX();
        int yCenter = center.getBlockY();
        int zCenter = center.getBlockZ();

        for (int x = xCenter - radius; x <= xCenter + radius; x++) {
            for (int z = zCenter - radius; z <= zCenter + radius; z++) {
                if (Math.sqrt(Math.pow(x - xCenter, 2) + Math.pow(z - zCenter, 2)) <= radius) {
                    for (int y = yCenter; y < yCenter + height; y++) {
                        updateBlock(world.getBlockAt(x, y, z), material, changes);
                    }
                }
            }
        }
        finalizeBuild(player, changes);
    }

    private void buildSphere(Player player, Material material, int radius) {
        Location center = validateSinglePoint(player);
        if (center == null) return;

        List<BlockRecord> changes = new ArrayList<>();
        World world = center.getWorld();
        int xCenter = center.getBlockX();
        int yCenter = center.getBlockY();
        int zCenter = center.getBlockZ();

        for (int x = xCenter - radius; x <= xCenter + radius; x++) {
            for (int y = yCenter - radius; y <= yCenter + radius; y++) {
                for (int z = zCenter - radius; z <= zCenter + radius; z++) {
                    double distance = Math.sqrt(Math.pow(x - xCenter, 2) +
                            Math.pow(y - yCenter, 2) +
                            Math.pow(z - zCenter, 2));
                    if (distance <= radius + 0.5) {
                        updateBlock(world.getBlockAt(x, y, z), material, changes);
                    }
                }
            }
        }
        finalizeBuild(player, changes);
    }

    private void finalizeBuild(Player player, List<BlockRecord> changes) {
        if (changes.isEmpty()) return;

        history.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>()).addLast(changes);
        if (history.get(player.getUniqueId()).size() > MAX_HISTORY) {
            history.get(player.getUniqueId()).removeFirst();
        }

        player.sendMessage(ChatColor.GREEN + "成功建造 " + changes.size() + " 方块");
        playEffect(player.getLocation(), Color.ORANGE);
    }

    private void saveBlueprint(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "用法: /awand save <名称>");
            return;
        }

        Location[] points = validateSelection(player);
        if (points == null) return;

        List<BlockVector> vectors = new ArrayList<>();
        World world = points[0].getWorld();
        Block origin = world.getBlockAt(points[0]);

        for (Block block : getSelectedBlocks(points)) {
            vectors.add(new BlockVector(
                    block.getX() - origin.getX(),
                    block.getY() - origin.getY(),
                    block.getZ() - origin.getZ(),
                    block.getType()
            ));
        }

        if (vectors.size() > MAX_BLUEPRINT_SIZE) {
            player.sendMessage(ChatColor.RED + "蓝图尺寸过大 (最大 " + MAX_BLUEPRINT_SIZE + ")");
            return;
        }

        blueprints.put(args[1].toLowerCase(), vectors);
        player.sendMessage(ChatColor.GREEN + "蓝图保存成功: " + args[1]);
    }

    private void loadBlueprint(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "用法: /awand load <名称>");
            return;
        }

        List<BlockVector> blueprint = blueprints.get(args[1].toLowerCase());
        if (blueprint == null) {
            player.sendMessage(ChatColor.RED + "找不到该蓝图");
            return;
        }

        Block origin = player.getTargetBlock(null, 10);
        List<BlockRecord> changes = new ArrayList<>();

        for (BlockVector vector : blueprint) {
            Block block = origin.getWorld().getBlockAt(
                    origin.getX() + vector.dx,
                    origin.getY() + vector.dy,
                    origin.getZ() + vector.dz
            );
            updateBlock(block, vector.material, changes);
        }

        finalizeBuild(player, changes);
    }

    private void updateBlock(Block block, Material material, List<BlockRecord> changes) {
        if (block.getType() != material) {
            changes.add(new BlockRecord(block));
            block.setType(material);
        }
    }

    private Location[] validateSelection(Player player) {
        UUID uuid = player.getUniqueId();
        Location[] points = selections.get(uuid);

        // 检查是否选择了两个点
        if (points == null || points[0] == null || points[1] == null) {
            player.sendMessage(ChatColor.RED + "请先选择两个坐标点");
            return null;
        }

        // 检查两个点是否在同一个世界
        if (!points[0].getWorld().equals(points[1].getWorld())) {
            player.sendMessage(ChatColor.RED + "选择点必须在同一个世界");
            return null;
        }

        // 检查最大建造距离（防止滥用）
        if (points[0].distanceSquared(points[1]) > 1000000) { // 1000 blocks squared
            player.sendMessage(ChatColor.RED + "选择点之间距离过远（最大1000）");
            return null;
        }

        return points;
    }

    private static class BlockRecord {
        private final Location location;
        private final Material material;
        private final BlockData blockData;
        private final boolean wasAir;

        public BlockRecord(Block block) {
            this.location = block.getLocation();
            this.material = block.getType();
            this.blockData = block.getBlockData().clone(); // 保存完整的方块数据
            this.wasAir = block.getType().isAir();
        }

        public void restore() {
            Block block = location.getBlock();
            if (wasAir) {
                block.setType(Material.AIR, false);
            } else {
                block.setType(material, false);
                block.setBlockData(blockData, false); // 恢复完整的方块状态
            }
            // 触发物理更新
            block.getState().update(true, true);
        }
    }

    private record BlockVector(int dx, int dy, int dz, Material material) {
    }

    private void playEffect(Location loc, Color color) {
        loc.getWorld().spawnParticle(Particle.REDSTONE, loc, 10,
                new Particle.DustOptions(color, 1));
    }
}