package com.liverecord.farmframe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * s2e-farm-pro のアリーナ（フロア）を読み取り、その「1マス外側」を1マス幅で
 * 囲う農業デザインの枠（柵マスト）を生成する補助プラグイン。
 *
 *  /farmfence <番号>  … スタイル番号で枠を生成
 *  /farmfence clear   … 枠を撤去
 *  /farmfence list    … スタイル一覧
 *  /farmfence reload  … config 再読み込み
 *
 * 追加機能(v1.1.0): 爆発(TNT等)から枠を保護 / アリーナのサイズ変化に自動追従。
 */
public final class FarmFramePlugin extends JavaPlugin implements TabExecutor, Listener {

    private String sourcePlugin;
    private String worldOverride;
    private String anchor;
    private int centerXOffset;
    private int centerZOffset;
    private double sizeScale;
    private int sizePadding;

    private boolean protectExplosion;
    private boolean protectFire;
    private boolean protectBreak;
    private boolean clearOnFarmDelete;
    private boolean autoFollow;
    private int autoFollowInterval;
    private boolean autoBuild;
    private int autoStyleIndex;
    private int lastStyleIndex = -1; // 最後に建てたスタイル（clear しても保持・自動生成に使う）
    private String lastArenaSig = null;

    private int gap;
    private int gapNorth; // -Z
    private int gapSouth; // +Z
    private int gapEast;  // +X
    private int gapWest;  // -X
    private int baseYOffset;
    private boolean cornerEnabled;
    private int postHeight;
    private boolean lightEnabled;
    private int lightSpacing;

    private final List<Style> styles = new ArrayList<>();

    // 現在設置されている枠の状態（state.yml に永続化）。
    private boolean frameActive;
    private int frameStyleIndex = -1;
    private String frameWorld;
    private int fMinX;
    private int fMaxX;
    private int fMinZ;
    private int fMaxZ;
    private int fBaseY;
    private int fPostHeight;
    private final Set<Material> frameMaterials = new HashSet<>();

    private int followTaskId = -1;

    /** 枠のデザイン1種。 */
    private static final class Style {
        final String name;
        final Material base;
        final Material fence;
        final Material post;
        final Material top;
        final Material light;

        Style(String name, Material base, Material fence, Material post, Material top, Material light) {
            this.name = name;
            this.base = base;
            this.fence = fence;
            this.post = post;
            this.top = top;
            this.light = light;
        }
    }

    /** 読み取ったアリーナ範囲（ブロック境界、両端含む）。 */
    private static final class Arena {
        final World world;
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        final int y;

        Arena(World world, int minX, int minZ, int maxX, int maxZ, int y) {
            this.world = world;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.y = y;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true);
        }
        loadSettings();
        loadState();
        getCommand("farmfence").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        startFollowTask();
        getLogger().info("FarmFrame 有効化。/farmfence（スタイル " + styles.size() + " 種）"
                + " / 保護[爆発=" + protectExplosion + " 火=" + protectFire + " 破壊=" + protectBreak + "]"
                + " / 自動追従=" + autoFollow);
    }

    @Override
    public void onDisable() {
        stopFollowTask();
    }

    private void loadSettings() {
        reloadConfig();
        sourcePlugin = getConfig().getString("source-plugin", "s2e-farm-pro");
        worldOverride = getConfig().getString("world", "");
        anchor = getConfig().getString("arena.anchor", "center").toLowerCase(Locale.ROOT);
        centerXOffset = getConfig().getInt("arena.center-x-offset", 0);
        centerZOffset = getConfig().getInt("arena.center-z-offset", 0);
        sizeScale = getConfig().getDouble("arena.size-scale", 2.0);
        sizePadding = getConfig().getInt("arena.size-padding", 0);

        protectExplosion = getConfig().getBoolean("protect.explosion", true);
        protectFire = getConfig().getBoolean("protect.fire", true);
        protectBreak = getConfig().getBoolean("protect.break", true);
        clearOnFarmDelete = getConfig().getBoolean("clear-on-farm-delete", true);
        autoFollow = getConfig().getBoolean("auto-follow.enabled", true);
        autoFollowInterval = Math.max(1, getConfig().getInt("auto-follow.interval-seconds", 5));
        autoBuild = getConfig().getBoolean("auto-follow.auto-build", true);
        autoStyleIndex = Math.max(0, getConfig().getInt("auto-follow.style", 1) - 1);

        gap = Math.max(0, getConfig().getInt("frame.gap", 1));
        gapNorth = Math.max(0, getConfig().getInt("frame.gap-north", gap));
        gapSouth = Math.max(0, getConfig().getInt("frame.gap-south", gap));
        gapEast = Math.max(0, getConfig().getInt("frame.gap-east", gap));
        gapWest = Math.max(0, getConfig().getInt("frame.gap-west", gap));
        baseYOffset = getConfig().getInt("frame.base-y-offset", 0);
        cornerEnabled = getConfig().getBoolean("frame.corner.enabled", true);
        postHeight = Math.max(1, getConfig().getInt("frame.corner.post-height", 2));
        lightEnabled = getConfig().getBoolean("frame.light.enabled", true);
        lightSpacing = Math.max(0, getConfig().getInt("frame.light.spacing", 4));

        styles.clear();
        List<Map<?, ?>> list = getConfig().getMapList("styles");
        for (Map<?, ?> m : list) {
            String name = str(m.get("name"), "枠");
            Material base = mat(m.get("base"), Material.HAY_BLOCK);
            Material fence = mat(m.get("fence"), Material.OAK_FENCE);
            Material post = mat(m.get("post"), Material.OAK_LOG);
            Material top = mat(m.get("top"), Material.LANTERN);
            Material light = mat(m.get("light"), Material.LANTERN);
            styles.add(new Style(name, base, fence, post, top, light));
        }
        if (styles.isEmpty()) {
            styles.add(new Style("木の牧場", Material.HAY_BLOCK, Material.OAK_FENCE,
                    Material.OAK_LOG, Material.LANTERN, Material.LANTERN));
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private Material mat(Object o, Material def) {
        if (o == null) {
            return def;
        }
        Material m = Material.matchMaterial(String.valueOf(o).trim());
        return m == null ? def : m;
    }

    // ===================== コマンド =====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("farmfence")) {
            return false;
        }
        if (!sender.hasPermission("farmfence.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e使い方: /farmfence <1-" + styles.size() + "|list|clear|reload>");
            return true;
        }
        String a = args[0].toLowerCase(Locale.ROOT);

        if (a.equals("reload")) {
            loadSettings();
            startFollowTask();
            sender.sendMessage("§aconfig.yml を再読み込みしました。（スタイル " + styles.size()
                    + " 種 / 爆発保護=" + protectExplosion + " / 自動追従=" + autoFollow + "）");
            return true;
        }
        if (a.equals("list")) {
            sender.sendMessage("§e=== FarmFrame スタイル ===");
            for (int i = 0; i < styles.size(); i++) {
                Style s = styles.get(i);
                sender.sendMessage("§7" + (i + 1) + ". §f" + s.name
                        + " §7(" + s.fence.name() + " / " + s.base.name() + ")");
            }
            return true;
        }

        if (a.equals("clear") || a.equals("off") || a.equals("remove")) {
            int n = clearStoredFrame();
            frameActive = false;
            frameStyleIndex = -1;
            saveState();
            sender.sendMessage("§a枠を撤去しました。§7(" + n + " ブロック)");
            return true;
        }

        Arena arena = readArena();
        if (arena == null) {
            sender.sendMessage("§c" + sourcePlugin + " のフロア設定を読めませんでした"
                    + "（plugins/" + sourcePlugin + "/config.yml とワールドを確認）。");
            return true;
        }

        int index;
        try {
            index = Integer.parseInt(a) - 1;
        } catch (NumberFormatException e) {
            sender.sendMessage("§c番号・list・clear・reload のいずれかを指定してください。");
            return true;
        }
        if (index < 0 || index >= styles.size()) {
            sender.sendMessage("§c1〜" + styles.size() + " で指定してください。");
            return true;
        }

        long t = System.currentTimeMillis();
        int n = rebuild(arena, index);
        long ms = System.currentTimeMillis() - t;
        Style style = styles.get(index);
        sender.sendMessage("§a農場わく「" + style.name + "」を生成しました。§7("
                + n + " ブロック / " + ms + "ms)");
        sender.sendMessage("§7範囲: X " + (arena.minX - gapWest) + "〜" + (arena.maxX + gapEast)
                + " / Z " + (arena.minZ - gapNorth) + "〜" + (arena.maxZ + gapSouth)
                + " / Y " + (arena.y + baseYOffset)
                + ((protectExplosion || protectFire || protectBreak) ? " §7(破壊耐性ON)" : "")
                + (autoFollow ? " §7(サイズ追従ON)" : ""));
        if (n == 0) {
            sender.sendMessage("§e※ 0ブロックでした。anchor/オフセット/ワールドを確認してください。");
        }
        return true;
    }

    // ===================== アリーナ読み取り =====================

    /** s2e-farm-pro の config.yml からアリーナ範囲を読む。読めなければ null。 */
    private Arena readArena() {
        File f = new File(getDataFolder().getParentFile(), sourcePlugin + "/config.yml");
        if (!f.exists()) {
            return null;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        if (!y.contains("arena.location.x")) {
            return null;
        }
        World world = resolveWorld(y.getString("arena.location.world_key"));
        if (world == null) {
            return null;
        }
        int cx = (int) Math.floor(y.getDouble("arena.location.x")) + centerXOffset;
        int cz = (int) Math.floor(y.getDouble("arena.location.z")) + centerZOffset;
        int baseY = (int) Math.floor(y.getDouble("arena.location.y"));
        int rawX = Math.max(1, y.getInt("arena.sizeX", 15));
        int rawZ = Math.max(1, y.getInt("arena.sizeZ", 15));

        int minX;
        int minZ;
        int maxX;
        int maxZ;
        if (anchor.equals("corner")) {
            // location をアリーナの角とみなす（直径モデル）。
            int sizeX = Math.max(1, (int) Math.round(rawX * sizeScale) + sizePadding);
            int sizeZ = Math.max(1, (int) Math.round(rawZ * sizeScale) + sizePadding);
            minX = cx;
            minZ = cz;
            maxX = cx + sizeX - 1;
            maxZ = cz + sizeZ - 1;
        } else {
            // location をアリーナ中心とみなす（半径モデル）。
            // sizeX は中心からの半径＝アリーナは 中心±sizeX で 2*sizeX+1 ブロック（対称）。
            // 例: sizeX=15 → 中心±15 = 31×31（s2e-farm の実フロアと一致）。
            int radiusX = Math.max(0, (int) Math.round(rawX * sizeScale) + sizePadding);
            int radiusZ = Math.max(0, (int) Math.round(rawZ * sizeScale) + sizePadding);
            minX = cx - radiusX;
            maxX = cx + radiusX;
            minZ = cz - radiusZ;
            maxZ = cz + radiusZ;
        }
        return new Arena(world, minX, minZ, maxX, maxZ, baseY);
    }

    private World resolveWorld(String worldKey) {
        if (worldOverride != null && !worldOverride.isEmpty()) {
            World w = Bukkit.getWorld(worldOverride);
            if (w != null) {
                return w;
            }
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) {
                return w;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    // ===================== 生成・撤去 =====================

    /** 既存の枠を撤去してから、指定スタイルで作り直し、状態を保存する。 */
    private int rebuild(Arena arena, int styleIndex) {
        clearStoredFrame();
        Style style = styles.get(styleIndex);
        int n = buildFrame(arena, style);

        // 状態を保存（爆発保護・自動追従の基準）。
        frameActive = true;
        frameStyleIndex = styleIndex;
        lastStyleIndex = styleIndex; // clear しても保持（自動生成のスタイル）
        frameWorld = arena.world.getName();
        fMinX = arena.minX - gapWest;
        fMaxX = arena.maxX + gapEast;
        fMinZ = arena.minZ - gapNorth;
        fMaxZ = arena.maxZ + gapSouth;
        fBaseY = arena.y + baseYOffset;
        fPostHeight = postHeight;
        frameMaterials.clear();
        frameMaterials.add(style.base);
        frameMaterials.add(style.fence);
        frameMaterials.add(style.post);
        frameMaterials.add(style.top);
        frameMaterials.add(style.light);
        saveState();
        return n;
    }

    /** 外周リング（アリーナの gap マス外側・1マス幅）に枠を設置する。 */
    private int buildFrame(Arena arena, Style style) {
        World w = arena.world;
        int fx0 = arena.minX - gapWest;
        int fx1 = arena.maxX + gapEast;
        int fz0 = arena.minZ - gapNorth;
        int fz1 = arena.maxZ + gapSouth;
        int baseY = arena.y + baseYOffset;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        int changed = 0;

        for (int x = fx0; x <= fx1; x++) {
            for (int z = fz0; z <= fz1; z++) {
                boolean edge = (x == fx0 || x == fx1 || z == fz0 || z == fz1);
                if (!edge) {
                    continue;
                }
                boolean corner = (x == fx0 || x == fx1) && (z == fz0 || z == fz1);

                changed += set(w, x, baseY, z, style.base, minY, maxY);
                if (corner && cornerEnabled) {
                    int yy = baseY + 1;
                    for (int h = 0; h < postHeight; h++, yy++) {
                        changed += set(w, x, yy, z, style.post, minY, maxY);
                    }
                    changed += set(w, x, yy, z, style.top, minY, maxY);
                } else {
                    changed += set(w, x, baseY + 1, z, style.fence, minY, maxY);
                    if (lightEnabled && lightSpacing > 0 && ((x + z) % lightSpacing == 0)) {
                        changed += set(w, x, baseY + 2, z, style.light, minY, maxY);
                    }
                }
            }
        }
        return changed;
    }

    /**
     * 保存済みの枠領域を撤去する。アリーナ拡大で枠位置がプレイ面に変わっても巻き込まないよう、
     * 枠の素材に一致するブロックだけを空気に戻す（マテリアル対応）。
     * @return 撤去したブロック数
     */
    private int clearStoredFrame() {
        if (!frameActive || frameWorld == null) {
            return 0;
        }
        World w = Bukkit.getWorld(frameWorld);
        if (w == null) {
            return 0;
        }
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        int top = fBaseY + fPostHeight + 1;
        int changed = 0;
        for (int x = fMinX; x <= fMaxX; x++) {
            for (int z = fMinZ; z <= fMaxZ; z++) {
                boolean edge = (x == fMinX || x == fMaxX || z == fMinZ || z == fMaxZ);
                if (!edge) {
                    continue;
                }
                for (int yy = fBaseY; yy <= top; yy++) {
                    if (yy < minY || yy > maxY) {
                        continue;
                    }
                    Block b = w.getBlockAt(x, yy, z);
                    if (frameMaterials.contains(b.getType())) {
                        b.setType(Material.AIR, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private int set(World w, int x, int y, int z, Material m, int minY, int maxY) {
        if (y < minY || y > maxY) {
            return 0;
        }
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != m) {
            b.setType(m, false);
            return 1;
        }
        return 0;
    }

    // ===================== 爆発保護 =====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (protectExplosion && frameActive) {
            e.blockList().removeIf(b -> isFrameBlock(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (protectExplosion && frameActive) {
            e.blockList().removeIf(b -> isFrameBlock(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        }
    }

    /** 燃焼で枠が消えるのを防ぐ（干し草/木の柵が燃え尽きない）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        if (protectFire && frameActive && isFrameBlock(blockWorld(e.getBlock()), e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ())) {
            e.setCancelled(true);
        }
    }

    /** 枠の上/隣に火がつくのを防ぐ（落雷の着火を含む）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent e) {
        if (protectFire && frameActive && touchesFrame(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    /** 火の延焼が枠へ及ぶのを防ぐ。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) {
        if (protectFire && frameActive && e.getSource().getType() == Material.FIRE && touchesFrame(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    /** プレイヤー等による枠ブロックの破壊を防ぐ。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (protectBreak && frameActive && isFrameBlock(blockWorld(e.getBlock()), e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ())) {
            e.setCancelled(true);
        }
    }

    /** エンダーマン等による枠ブロックの変化を防ぐ。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (protectBreak && frameActive && isFrameBlock(blockWorld(e.getBlock()), e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ())) {
            e.setCancelled(true);
        }
    }

    private String blockWorld(Block b) {
        return b.getWorld().getName();
    }

    // ===================== /farm delete 連動撤去 =====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        maybeHandleFarmDelete(e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        maybeHandleFarmDelete("/" + e.getCommand());
    }

    /** /farm delete を検知したら枠を撤去し、自動生成が即再建しないよう抑制する。 */
    private void maybeHandleFarmDelete(String raw) {
        if (!clearOnFarmDelete || raw == null) {
            return;
        }
        String s = raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        String[] a = s.trim().split("\\s+");
        if (a.length < 2) {
            return;
        }
        String cmd = a[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');
        if (colon >= 0) {
            cmd = cmd.substring(colon + 1);
        }
        if (!cmd.equals("farm") || !a[1].equalsIgnoreCase("delete")) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int n = clearStoredFrame();
            frameActive = false;
            frameStyleIndex = -1;
            saveState();
            // 削除後のアリーナ署名を記録し、auto-build が即再生成しないよう抑制。
            Arena after = readArena();
            if (after != null) {
                lastArenaSig = after.world.getName() + ":" + after.minX + "," + after.maxX + ","
                        + after.minZ + "," + after.maxZ + "," + after.y;
            }
            getLogger().info("[farmframe] /farm delete を検知 → 枠を撤去しました（" + n + " ブロック）。");
        }, 2L);
    }

    /** 対象ブロック自身か、その6近傍が枠ブロックなら true（火を枠の周囲で止めるため）。 */
    private boolean touchesFrame(Block b) {
        String w = b.getWorld().getName();
        int x = b.getX();
        int y = b.getY();
        int z = b.getZ();
        return isFrameBlock(w, x, y, z)
                || isFrameBlock(w, x + 1, y, z) || isFrameBlock(w, x - 1, y, z)
                || isFrameBlock(w, x, y, z + 1) || isFrameBlock(w, x, y, z - 1)
                || isFrameBlock(w, x, y + 1, z) || isFrameBlock(w, x, y - 1, z);
    }

    /** その座標が現在の枠（外周リング＋高さ範囲）に属するか。 */
    private boolean isFrameBlock(String world, int x, int y, int z) {
        if (!frameActive || frameWorld == null || !frameWorld.equals(world)) {
            return false;
        }
        if (x < fMinX || x > fMaxX || z < fMinZ || z > fMaxZ) {
            return false;
        }
        boolean edge = (x == fMinX || x == fMaxX || z == fMinZ || z == fMaxZ);
        if (!edge) {
            return false;
        }
        return y >= fBaseY && y <= fBaseY + fPostHeight + 1;
    }

    // ===================== 自動追従 =====================

    private void startFollowTask() {
        stopFollowTask();
        if (!autoFollow) {
            return;
        }
        long period = autoFollowInterval * 20L;
        followTaskId = Bukkit.getScheduler().runTaskTimer(this, this::followTick, period, period).getTaskId();
    }

    private void stopFollowTask() {
        if (followTaskId != -1) {
            Bukkit.getScheduler().cancelTask(followTaskId);
            followTaskId = -1;
        }
    }

    /**
     * アリーナの状態を監視し、
     *  - 既存の枠があればサイズ/位置変化に追従して作り直す、
     *  - 枠が無くても auto-build が有効なら /farm create 等の検知で自動生成する。
     */
    private void followTick() {
        Arena arena = readArena();
        if (arena == null) {
            return;
        }
        String sig = arena.world.getName() + ":" + arena.minX + "," + arena.maxX + ","
                + arena.minZ + "," + arena.maxZ + "," + arena.y;
        boolean changed = !sig.equals(lastArenaSig);
        lastArenaSig = sig;
        if (!changed) {
            return;
        }

        if (frameActive && frameStyleIndex >= 0 && frameStyleIndex < styles.size()) {
            getLogger().info("[farmframe] アリーナ変化を検知 → 枠を作り直します。");
            rebuild(arena, frameStyleIndex);
        } else if (autoBuild) {
            int style = (lastStyleIndex >= 0 && lastStyleIndex < styles.size())
                    ? lastStyleIndex : clampStyleIndex(autoStyleIndex);
            getLogger().info("[farmframe] アリーナを検知 → 枠を自動生成します（style " + (style + 1) + "）。");
            rebuild(arena, style);
        }
    }

    private int clampStyleIndex(int i) {
        if (styles.isEmpty()) {
            return 0;
        }
        if (i < 0) {
            return 0;
        }
        return Math.min(i, styles.size() - 1);
    }

    // ===================== 状態の永続化 =====================

    private File stateFile() {
        return new File(getDataFolder(), "state.yml");
    }

    private void saveState() {
        YamlConfiguration s = new YamlConfiguration();
        s.set("active", frameActive);
        s.set("style-index", frameStyleIndex);
        s.set("last-style-index", lastStyleIndex);
        s.set("world", frameWorld);
        s.set("min-x", fMinX);
        s.set("max-x", fMaxX);
        s.set("min-z", fMinZ);
        s.set("max-z", fMaxZ);
        s.set("base-y", fBaseY);
        s.set("post-height", fPostHeight);
        List<String> mats = new ArrayList<>();
        for (Material m : frameMaterials) {
            mats.add(m.name());
        }
        s.set("materials", mats);
        try {
            s.save(stateFile());
        } catch (Exception e) {
            getLogger().warning("state.yml の保存に失敗: " + e);
        }
    }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration s = YamlConfiguration.loadConfiguration(f);
        frameActive = s.getBoolean("active", false);
        frameStyleIndex = s.getInt("style-index", -1);
        lastStyleIndex = s.getInt("last-style-index", frameStyleIndex);
        frameWorld = s.getString("world");
        fMinX = s.getInt("min-x");
        fMaxX = s.getInt("max-x");
        fMinZ = s.getInt("min-z");
        fMaxZ = s.getInt("max-z");
        fBaseY = s.getInt("base-y");
        fPostHeight = s.getInt("post-height", postHeight);
        frameMaterials.clear();
        for (String name : s.getStringList("materials")) {
            Material m = Material.matchMaterial(name);
            if (m != null) {
                frameMaterials.add(m);
            }
        }
    }

    // ===================== タブ補完 =====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> opts = new ArrayList<>();
            for (int i = 1; i <= styles.size(); i++) {
                opts.add(String.valueOf(i));
            }
            opts.add("list");
            opts.add("clear");
            opts.add("reload");
            for (String o : opts) {
                if (o.startsWith(pre)) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
