package com.liverecord.farmframe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

/**
 * s2e-farm-pro のアリーナ（フロア）を読み取り、その「1マス外側」を1マス幅で 囲う農業デザインの枠（柵マスト）を生成する補助プラグイン。
 *
 * <p>コマンド:
 *
 * <ul>
 *   <li>{@code /farmfence <番号>} - スタイル番号で枠を生成
 *   <li>{@code /farmfence clear} - 枠を撤去
 *   <li>{@code /farmfence list} - スタイル一覧
 *   <li>{@code /farmfence reload} - config 再読み込み
 * </ul>
 *
 * <p>追加機能(v1.1.0): 爆発(TNT等)から枠を保護 / アリーナのサイズ変化に自動追従。
 *
 * <p>追加機能(v1.10.0): アリーナ拡張時に浮いたランタン等が落下してドロップアイテム化し 農場内に残る問題を修正（枠領域に落ちた枠素材のアイテムを撤去）。
 *
 * @version 1.10.0
 * @author LiveRecord
 */
public final class FarmFramePlugin extends JavaPlugin implements TabExecutor, Listener {

  // ===================== 定数 =====================

  /** メインコマンド名。 */
  private static final String COMMAND_NAME = "farmfence";

  /** スタイル名のデフォルト値。 */
  private static final String DEFAULT_STYLE_NAME = "枠";

  /** /farm delete 連動撤去の遅延tick数。コマンド処理完了を待つため。 */
  private static final long FARM_DELETE_DELAY_TICKS = 2L;

  /** 自動追従の最小間隔（秒）。 */
  private static final int MIN_FOLLOW_INTERVAL = 1;

  /** gap の最小値。 */
  private static final int MIN_GAP = 0;

  /** 角柱の最小高さ。 */
  private static final int MIN_POST_HEIGHT = 1;

  /** 明かり間隔の最小値（0で無効）。 */
  private static final int MIN_LIGHT_SPACING = 0;

  /** アリーナサイズの最小値。 */
  private static final int MIN_SIZE = 1;

  /** 1秒あたりのtick数。 */
  private static final int TICKS_PER_SECOND = 20;

  // ===================== 設定フィールド =====================

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
  private boolean clearDroppedItems;
  private boolean autoFollow;
  private int autoFollowInterval;
  private boolean autoBuild;
  private int autoStyleIndex;

  /** 最後に建てたスタイル（clear しても保持・自動生成に使う）。 */
  private int lastStyleIndex = -1;

  /** 前回のアリーナ署名（自動追従の変化検知用）。 */
  private String lastArenaSig = null;

  private int gap;
  private int gapNorth;
  private int gapSouth;
  private int gapEast;
  private int gapWest;
  private int baseYOffset;
  private boolean cornerEnabled;
  private int postHeight;
  private boolean lightEnabled;
  private int lightSpacing;

  private final List<Style> styles = new ArrayList<>();

  // ===================== 枠の状態フィールド（state.yml に永続化） =====================

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

  /** 自動追従タスクの ID（-1=未登録）。 */
  private int followTaskId = -1;

  // ===================== 内部データクラス =====================

  /** 枠のデザイン1種。 base(土台)・fence(柵)・post(角柱)・top(飾り)・light(明かり)の5素材で構成。 */
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

  // ===================== ライフサイクル =====================

  /** プラグイン有効化。設定読み込み・状態復元・コマンド登録・イベント登録・追従タスク開始。 */
  @Override
  public void onEnable() {
    saveDefaultConfig();
    if (getResource("README.md") != null) {
      saveResource("README.md", true);
    }
    loadSettings();
    loadState();
    getCommand(COMMAND_NAME).setExecutor(this);
    getServer().getPluginManager().registerEvents(this, this);
    startFollowTask();
    removeNamespacedAliases();
    getLogger()
        .info(
            "FarmFrame 有効化。/farmfence（スタイル "
                + styles.size()
                + " 種）"
                + " / 保護[爆発="
                + protectExplosion
                + " 火="
                + protectFire
                + " 破壊="
                + protectBreak
                + "]"
                + " / 自動追従="
                + autoFollow);
  }

  /** タブ補完に出る「プラグイン名:コマンド名」形式の名前空間エイリアスをコマンドマップから削除する。 */
  @SuppressWarnings("unchecked")
  private void removeNamespacedAliases() {
    try {
      Object server = getServer();
      java.lang.reflect.Method getMap = server.getClass().getMethod("getCommandMap");
      getMap.setAccessible(true);
      Object commandMap = getMap.invoke(server);

      java.lang.reflect.Field f = null;
      for (Class<?> c = commandMap.getClass(); c != null; c = c.getSuperclass()) {
        try {
          f = c.getDeclaredField("knownCommands");
          break;
        } catch (NoSuchFieldException ignored) {
        }
      }
      if (f == null) {
        getLogger()
            .warning("[alias] knownCommands フィールドが見つかりません: " + commandMap.getClass().getName());
        return;
      }
      f.setAccessible(true);
      java.util.Map<String, ?> known = (java.util.Map<String, ?>) f.get(commandMap);
      String prefix = getName().toLowerCase(java.util.Locale.ROOT) + ":";
      boolean removed = known.keySet().removeIf(k -> k.startsWith(prefix));
      getLogger().info("[alias] " + prefix + "* の名前空間エイリアス削除: " + (removed ? "成功" : "キーなし"));

      try {
        java.lang.reflect.Method sync = server.getClass().getMethod("syncCommands");
        sync.setAccessible(true);
        sync.invoke(server);
      } catch (Exception e) {
        getLogger().warning("[alias] syncCommands 失敗: " + e.getMessage());
      }
    } catch (Exception e) {
      getLogger().warning("[alias] 名前空間エイリアス削除失敗: " + e);
    }
  }

  /** プラグイン無効化。追従タスクを停止する。 */
  @Override
  public void onDisable() {
    stopFollowTask();
  }

  // ===================== 設定読み込み =====================

  /** config.yml を読み込み、全設定値をフィールドに反映する。 */
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
    clearDroppedItems = getConfig().getBoolean("frame.clear-dropped-items", true);
    autoFollow = getConfig().getBoolean("auto-follow.enabled", true);
    autoFollowInterval =
        Math.max(MIN_FOLLOW_INTERVAL, getConfig().getInt("auto-follow.interval-seconds", 5));
    autoBuild = getConfig().getBoolean("auto-follow.auto-build", true);
    autoStyleIndex = Math.max(0, getConfig().getInt("auto-follow.style", 1) - 1);

    loadFrameSettings();
    loadStyles();
  }

  /** frame セクションの設定を読み込む。 */
  private void loadFrameSettings() {
    gap = Math.max(MIN_GAP, getConfig().getInt("frame.gap", 1));
    gapNorth = Math.max(MIN_GAP, getConfig().getInt("frame.gap-north", gap));
    gapSouth = Math.max(MIN_GAP, getConfig().getInt("frame.gap-south", gap));
    gapEast = Math.max(MIN_GAP, getConfig().getInt("frame.gap-east", gap));
    gapWest = Math.max(MIN_GAP, getConfig().getInt("frame.gap-west", gap));
    baseYOffset = getConfig().getInt("frame.base-y-offset", 0);
    cornerEnabled = getConfig().getBoolean("frame.corner.enabled", true);
    postHeight = Math.max(MIN_POST_HEIGHT, getConfig().getInt("frame.corner.post-height", 2));
    lightEnabled = getConfig().getBoolean("frame.light.enabled", true);
    lightSpacing = Math.max(MIN_LIGHT_SPACING, getConfig().getInt("frame.light.spacing", 4));
  }

  /** styles セクションからスタイル定義を読み込む。 空の場合はデフォルトスタイルを1種追加する。 */
  private void loadStyles() {
    styles.clear();
    final List<Map<?, ?>> list = getConfig().getMapList("styles");
    for (final Map<?, ?> m : list) {
      final String name = str(m.get("name"), DEFAULT_STYLE_NAME);
      final Material base = mat(m.get("base"), Material.HAY_BLOCK);
      final Material fence = mat(m.get("fence"), Material.OAK_FENCE);
      final Material post = mat(m.get("post"), Material.OAK_LOG);
      final Material top = mat(m.get("top"), Material.LANTERN);
      final Material light = mat(m.get("light"), Material.LANTERN);
      styles.add(new Style(name, base, fence, post, top, light));
    }
    if (styles.isEmpty()) {
      styles.add(
          new Style(
              "木の牧場",
              Material.HAY_BLOCK,
              Material.OAK_FENCE,
              Material.OAK_LOG,
              Material.LANTERN,
              Material.LANTERN));
    }
  }

  /**
   * null 安全な文字列変換。
   *
   * @param obj 変換対象（null 可）
   * @param def null 時のデフォルト値
   * @return 文字列表現またはデフォルト値
   */
  private static String str(final Object obj, final String def) {
    return obj == null ? def : String.valueOf(obj);
  }

  /**
   * null 安全なマテリアル変換。 Material.matchMaterial で解決できない場合はデフォルト値を返す。
   *
   * @param obj 変換対象（null 可）
   * @param def null 時またはマッチ失敗時のデフォルト値
   * @return 解決された Material またはデフォルト値
   */
  private Material mat(final Object obj, final Material def) {
    if (obj == null) {
      return def;
    }
    final Material m = Material.matchMaterial(String.valueOf(obj).trim());
    return m == null ? def : m;
  }

  // ===================== コマンド =====================

  /**
   * /farmfence コマンドの実行処理。
   *
   * @param sender コマンド送信者
   * @param command コマンドオブジェクト
   * @param label 使用されたコマンドラベル
   * @param args コマンド引数
   * @return コマンドが処理されたか
   */
  @Override
  public boolean onCommand(
      final CommandSender sender, final Command command, final String label, final String[] args) {
    if (!command.getName().equalsIgnoreCase(COMMAND_NAME)) {
      return false;
    }
    if (!sender.hasPermission("farmfence.use")) {
      sender.sendMessage("§cこのコマンドを使う権限がありません。");
      return true;
    }
    if (args.length == 0) {
      sendUsage(sender);
      return true;
    }

    final String subCommand = args[0].toLowerCase(Locale.ROOT);
    switch (subCommand) {
      case "reload":
        handleReload(sender);
        return true;
      case "list":
        handleList(sender);
        return true;
      case "clear":
      case "off":
      case "remove":
        handleClear(sender);
        return true;
      default:
        handleBuild(sender, subCommand);
        return true;
    }
  }

  /**
   * 使い方メッセージを送信する。
   *
   * @param sender メッセージ送信先
   */
  private void sendUsage(final CommandSender sender) {
    sender.sendMessage("§e使い方: /farmfence <1-" + styles.size() + "|list|clear|reload>");
  }

  /**
   * reload サブコマンドの処理。設定を再読み込みし、追従タスクを再起動する。
   *
   * @param sender コマンド送信者
   */
  private void handleReload(final CommandSender sender) {
    loadSettings();
    startFollowTask();
    sender.sendMessage(
        "§aconfig.yml を再読み込みしました。（スタイル "
            + styles.size()
            + " 種 / 爆発保護="
            + protectExplosion
            + " / 自動追従="
            + autoFollow
            + "）");
  }

  /**
   * list サブコマンドの処理。スタイル一覧を表示する。
   *
   * @param sender コマンド送信者
   */
  private void handleList(final CommandSender sender) {
    sender.sendMessage("§e=== FarmFrame スタイル ===");
    for (int i = 0; i < styles.size(); i++) {
      final Style s = styles.get(i);
      sender.sendMessage(
          "§7" + (i + 1) + ". §f" + s.name + " §7(" + s.fence.name() + " / " + s.base.name() + ")");
    }
  }

  /**
   * clear サブコマンドの処理。枠を撤去する。
   *
   * @param sender コマンド送信者
   */
  private void handleClear(final CommandSender sender) {
    final int removed = clearStoredFrame();
    frameActive = false;
    frameStyleIndex = -1;
    saveState();
    sender.sendMessage("§a枠を撤去しました。§7(" + removed + " ブロック)");
  }

  /**
   * スタイル番号指定による枠生成の処理。
   *
   * @param sender コマンド送信者
   * @param arg 引数文字列（数値を期待）
   */
  private void handleBuild(final CommandSender sender, final String arg) {
    final Arena arena = readArena();
    if (arena == null) {
      sender.sendMessage(
          "§c"
              + sourcePlugin
              + " のフロア設定を読めませんでした"
              + "（plugins/"
              + sourcePlugin
              + "/config.yml とワールドを確認）。");
      return;
    }

    final int index;
    try {
      index = Integer.parseInt(arg) - 1;
    } catch (NumberFormatException e) {
      sender.sendMessage("§c番号・list・clear・reload のいずれかを指定してください。");
      return;
    }
    if (index < 0 || index >= styles.size()) {
      sender.sendMessage("§c1〜" + styles.size() + " で指定してください。");
      return;
    }

    final long startTime = System.currentTimeMillis();
    final int blockCount = rebuild(arena, index);
    final long elapsed = System.currentTimeMillis() - startTime;
    final Style style = styles.get(index);

    sendBuildResult(sender, arena, style, blockCount, elapsed);
  }

  /**
   * 枠生成の結果メッセージを送信する。
   *
   * @param sender メッセージ送信先
   * @param arena アリーナ範囲
   * @param style 使用したスタイル
   * @param blockCount 生成ブロック数
   * @param elapsed 処理時間（ミリ秒）
   */
  private void sendBuildResult(
      final CommandSender sender,
      final Arena arena,
      final Style style,
      final int blockCount,
      final long elapsed) {
    sender.sendMessage(
        "§a農場わく「" + style.name + "」を生成しました。§7(" + blockCount + " ブロック / " + elapsed + "ms)");

    final boolean hasProtection = protectExplosion || protectFire || protectBreak;
    sender.sendMessage(
        "§7範囲: X "
            + (arena.minX - gapWest)
            + "〜"
            + (arena.maxX + gapEast)
            + " / Z "
            + (arena.minZ - gapNorth)
            + "〜"
            + (arena.maxZ + gapSouth)
            + " / Y "
            + (arena.y + baseYOffset)
            + (hasProtection ? " §7(破壊耐性ON)" : "")
            + (autoFollow ? " §7(サイズ追従ON)" : ""));

    if (blockCount == 0) {
      sender.sendMessage("§e※ 0ブロックでした。" + "anchor/オフセット/ワールドを確認してください。");
    }
  }

  // ===================== アリーナ読み取り =====================

  /**
   * s2e-farm-pro の config.yml からアリーナ範囲を読む。
   *
   * @return アリーナ範囲。読めなければ null
   */
  private Arena readArena() {
    final File configFile = new File(getDataFolder().getParentFile(), sourcePlugin + "/config.yml");
    if (!configFile.exists()) {
      return null;
    }

    final YamlConfiguration yaml;
    try {
      yaml = YamlConfiguration.loadConfiguration(configFile);
    } catch (Exception e) {
      getLogger().warning("外部 config.yml の読み込みに失敗: " + e);
      return null;
    }

    if (!yaml.contains("arena.location.x")) {
      return null;
    }

    final World world = resolveWorld(yaml.getString("arena.location.world_key"));
    if (world == null) {
      return null;
    }

    final int cx = (int) Math.floor(yaml.getDouble("arena.location.x")) + centerXOffset;
    final int cz = (int) Math.floor(yaml.getDouble("arena.location.z")) + centerZOffset;
    final int baseY = (int) Math.floor(yaml.getDouble("arena.location.y"));
    final int rawX = Math.max(MIN_SIZE, yaml.getInt("arena.sizeX", 15));
    final int rawZ = Math.max(MIN_SIZE, yaml.getInt("arena.sizeZ", 15));

    return calculateArenaBounds(world, cx, cz, baseY, rawX, rawZ);
  }

  /**
   * anchor モードに応じてアリーナの座標範囲を計算する。
   *
   * @param world ワールド
   * @param cx 中心/角の X 座標
   * @param cz 中心/角の Z 座標
   * @param baseY 基準 Y 座標
   * @param rawX X方向の生サイズ
   * @param rawZ Z方向の生サイズ
   * @return 計算されたアリーナ範囲
   */
  private Arena calculateArenaBounds(
      final World world,
      final int cx,
      final int cz,
      final int baseY,
      final int rawX,
      final int rawZ) {
    final int minX;
    final int minZ;
    final int maxX;
    final int maxZ;

    if ("corner".equals(anchor)) {
      // location をアリーナの角とみなす（直径モデル）。
      final int sizeX = Math.max(MIN_SIZE, (int) Math.round(rawX * sizeScale) + sizePadding);
      final int sizeZ = Math.max(MIN_SIZE, (int) Math.round(rawZ * sizeScale) + sizePadding);
      minX = cx;
      minZ = cz;
      maxX = cx + sizeX - 1;
      maxZ = cz + sizeZ - 1;
    } else {
      // location をアリーナ中心とみなす（半径モデル）。
      // sizeX は中心からの半径。アリーナは 中心+-sizeX で
      // 2*sizeX+1 ブロック（対称）。
      // 例: sizeX=15 -> 中心+-15 = 31x31
      //     （s2e-farm の実フロアと一致）。
      final int radiusX = Math.max(0, (int) Math.round(rawX * sizeScale) + sizePadding);
      final int radiusZ = Math.max(0, (int) Math.round(rawZ * sizeScale) + sizePadding);
      minX = cx - radiusX;
      maxX = cx + radiusX;
      minZ = cz - radiusZ;
      maxZ = cz + radiusZ;
    }
    return new Arena(world, minX, minZ, maxX, maxZ, baseY);
  }

  /**
   * ワールド名を解決する。 worldOverride が設定されていればそれを優先し、 なければ NORMAL 環境のワールド、最後に先頭ワールドを返す。
   *
   * @param worldKey s2e-farm-pro の world_key（未使用だがインターフェース維持）
   * @return 解決されたワールド。見つからなければ null
   */
  private World resolveWorld(final String worldKey) {
    if (worldOverride != null && !worldOverride.isEmpty()) {
      final World w = Bukkit.getWorld(worldOverride);
      if (w != null) {
        return w;
      }
    }
    for (final World w : Bukkit.getWorlds()) {
      if (w.getEnvironment() == World.Environment.NORMAL) {
        return w;
      }
    }
    return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
  }

  // ===================== 生成・撤去 =====================

  /**
   * 既存の枠を撤去してから、指定スタイルで作り直し、状態を保存する。
   *
   * @param arena アリーナ範囲
   * @param styleIndex スタイルインデックス（0始まり）
   * @return 設置したブロック数
   */
  private int rebuild(final Arena arena, final int styleIndex) {
    clearStoredFrame();
    final Style style = styles.get(styleIndex);
    final int blockCount = buildFrame(arena, style);

    // 状態を保存（爆発保護・自動追従の基準）。
    updateFrameState(arena, styleIndex, style);
    saveState();
    return blockCount;
  }

  /**
   * 枠生成後の状態をフィールドに反映する。
   *
   * @param arena アリーナ範囲
   * @param styleIndex スタイルインデックス
   * @param style 使用したスタイル
   */
  private void updateFrameState(final Arena arena, final int styleIndex, final Style style) {
    frameActive = true;
    frameStyleIndex = styleIndex;
    lastStyleIndex = styleIndex;
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
  }

  /**
   * 外周リング（アリーナの gap マス外側・1マス幅）に枠を設置する。
   *
   * @param arena アリーナ範囲
   * @param style 使用するスタイル
   * @return 変更したブロック数
   */
  private int buildFrame(final Arena arena, final Style style) {
    final World w = arena.world;
    final int fx0 = arena.minX - gapWest;
    final int fx1 = arena.maxX + gapEast;
    final int fz0 = arena.minZ - gapNorth;
    final int fz1 = arena.maxZ + gapSouth;
    final int baseY = arena.y + baseYOffset;
    final int minY = w.getMinHeight();
    final int maxY = w.getMaxHeight() - 1;
    int changed = 0;

    for (int x = fx0; x <= fx1; x++) {
      for (int z = fz0; z <= fz1; z++) {
        final boolean edge = (x == fx0 || x == fx1 || z == fz0 || z == fz1);
        if (!edge) {
          continue;
        }
        final boolean corner = (x == fx0 || x == fx1) && (z == fz0 || z == fz1);

        changed += setBlock(w, x, baseY, z, style.base, minY, maxY);
        changed += buildColumnAt(w, x, baseY, z, corner, style, minY, maxY);
      }
    }
    return changed;
  }

  /**
   * 外周リングの1セルに柱または柵＋明かりを設置する。
   *
   * @param w ワールド
   * @param x X座標
   * @param baseY 基準Y座標
   * @param z Z座標
   * @param corner 角セルか
   * @param style 使用するスタイル
   * @param minY ワールド最小Y
   * @param maxY ワールド最大Y
   * @return 変更したブロック数
   */
  private int buildColumnAt(
      final World w,
      final int x,
      final int baseY,
      final int z,
      final boolean corner,
      final Style style,
      final int minY,
      final int maxY) {
    int changed = 0;
    if (corner && cornerEnabled) {
      int yy = baseY + 1;
      for (int h = 0; h < postHeight; h++, yy++) {
        changed += setBlock(w, x, yy, z, style.post, minY, maxY);
      }
      changed += setBlock(w, x, yy, z, style.top, minY, maxY);
    } else {
      changed += setBlock(w, x, baseY + 1, z, style.fence, minY, maxY);
      if (lightEnabled && lightSpacing > 0 && ((x + z) % lightSpacing == 0)) {
        changed += setBlock(w, x, baseY + 2, z, style.light, minY, maxY);
      }
    }
    return changed;
  }

  /**
   * 保存済みの枠領域を撤去する。 アリーナ拡大で枠位置がプレイ面に変わっても巻き込まないよう、 枠の素材に一致するブロックだけを空気に戻す（マテリアル照合）。
   *
   * @return 撤去したブロック数
   */
  private int clearStoredFrame() {
    if (!frameActive || frameWorld == null) {
      return 0;
    }
    final World w = Bukkit.getWorld(frameWorld);
    if (w == null) {
      return 0;
    }
    final int minY = w.getMinHeight();
    final int maxY = w.getMaxHeight() - 1;
    final int top = fBaseY + fPostHeight + 1;
    int changed = 0;

    for (int x = fMinX; x <= fMaxX; x++) {
      for (int z = fMinZ; z <= fMaxZ; z++) {
        final boolean edge = (x == fMinX || x == fMaxX || z == fMinZ || z == fMaxZ);
        if (!edge) {
          continue;
        }
        for (int yy = fBaseY; yy <= top; yy++) {
          if (yy < minY || yy > maxY) {
            continue;
          }
          final Block b = w.getBlockAt(x, yy, z);
          if (frameMaterials.contains(b.getType())) {
            b.setType(Material.AIR, false);
            changed++;
          }
        }
      }
    }
    // 旧枠領域に落下した枠素材のドロップアイテム（ランタン等）も撤去する。
    final int items = removeDroppedFrameItems(w);
    if (items > 0) {
      getLogger().info("[farmframe] 落下した枠アイテムを撤去しました（" + items + " 個）。");
    }
    return changed;
  }

  /**
   * 旧枠領域に落下した枠素材のドロップアイテムを撤去する。
   *
   * <p>アリーナ拡張時、s2e-farm がフロアを作り直す物理更新で、浮いていたランタン（柵の上）の 支えが外れてドロップアイテム化し、拡張後の農場内に残ることがある。ブロック撤去だけでは
   * この「落ちたアイテム」を消せないため、枠の X/Z 範囲（±1マス）に落ちた枠素材のアイテムを 除去する。
   *
   * @param w 対象ワールド
   * @return 撤去したアイテム数
   */
  private int removeDroppedFrameItems(final World w) {
    if (!clearDroppedItems || w == null || frameMaterials.isEmpty()) {
      return 0;
    }
    final int pad = 1;
    final double x0 = fMinX - pad;
    final double x1 = fMaxX + 1 + pad;
    final double z0 = fMinZ - pad;
    final double z1 = fMaxZ + 1 + pad;
    // 落下したアイテムはフロア付近に留まる。基準Yの少し下から柱の上まで。
    final double y0 = fBaseY - 3;
    final double y1 = fBaseY + fPostHeight + 4;
    int removed = 0;
    for (final org.bukkit.entity.Entity e : w.getEntities()) {
      if (!(e instanceof org.bukkit.entity.Item)) {
        continue;
      }
      final org.bukkit.Location loc = e.getLocation();
      if (loc.getX() < x0 || loc.getX() > x1 || loc.getZ() < z0 || loc.getZ() > z1) {
        continue;
      }
      if (loc.getY() < y0 || loc.getY() > y1) {
        continue;
      }
      final Material m = ((org.bukkit.entity.Item) e).getItemStack().getType();
      if (frameMaterials.contains(m)) {
        e.remove();
        removed++;
      }
    }
    return removed;
  }

  /**
   * 指定座標にブロックを設置する（ワールド高さ範囲チェック付き）。
   *
   * @param w ワールド
   * @param x X座標
   * @param y Y座標
   * @param z Z座標
   * @param m 設置するマテリアル
   * @param minY ワールド最小Y
   * @param maxY ワールド最大Y
   * @return 変更した場合 1、変更不要または範囲外なら 0
   */
  private int setBlock(
      final World w,
      final int x,
      final int y,
      final int z,
      final Material m,
      final int minY,
      final int maxY) {
    if (y < minY || y > maxY) {
      return 0;
    }
    final Block b = w.getBlockAt(x, y, z);
    if (b.getType() != m) {
      b.setType(m, false);
      return 1;
    }
    return 0;
  }

  // ===================== 爆発保護 =====================

  /**
   * エンティティ爆発から枠ブロックを保護する。
   *
   * @param e 爆発イベント
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityExplode(final EntityExplodeEvent e) {
    if (protectExplosion && frameActive) {
      e.blockList().removeIf(this::isFrameBlock);
    }
  }

  /**
   * ブロック爆発から枠ブロックを保護する。
   *
   * @param e 爆発イベント
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockExplode(final BlockExplodeEvent e) {
    if (protectExplosion && frameActive) {
      e.blockList().removeIf(this::isFrameBlock);
    }
  }

  // ===================== 火災保護 =====================

  /**
   * 燃焼で枠が消えるのを防ぐ（干し草/木の柵が燃え尽きない）。
   *
   * @param e 燃焼イベント
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockBurn(final BlockBurnEvent e) {
    if (protectFire && frameActive && isFrameBlock(e.getBlock())) {
      e.setCancelled(true);
    }
  }

  /**
   * 枠の上/隣に火がつくのを防ぐ（落雷の着火を含む）。
   *
   * @param e 着火イベント
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockIgnite(final BlockIgniteEvent e) {
    if (protectFire && frameActive && touchesFrame(e.getBlock())) {
      e.setCancelled(true);
    }
  }

  /**
   * 火の延焼が枠へ及ぶのを防ぐ。
   *
   * @param e 延焼イベント
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockSpread(final BlockSpreadEvent e) {
    if (protectFire
        && frameActive
        && e.getSource().getType() == Material.FIRE
        && touchesFrame(e.getBlock())) {
      e.setCancelled(true);
    }
  }

  // ===================== 破壊保護 =====================

  /**
   * プレイヤー等による枠ブロックの破壊を防ぐ。
   *
   * @param e 破壊イベント
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockBreak(final BlockBreakEvent e) {
    if (protectBreak && frameActive && isFrameBlock(e.getBlock())) {
      e.setCancelled(true);
    }
  }

  /**
   * エンダーマン等による枠ブロックの変化を防ぐ。
   *
   * @param e ブロック変化イベント
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityChangeBlock(final EntityChangeBlockEvent e) {
    if (protectBreak && frameActive && isFrameBlock(e.getBlock())) {
      e.setCancelled(true);
    }
  }

  // ===================== /farm delete 連動撤去 =====================

  /**
   * プレイヤーコマンドから /farm delete を検知する。
   *
   * @param e コマンドイベント
   */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlayerCommand(final PlayerCommandPreprocessEvent e) {
    maybeHandleFarmDelete(e.getMessage());
  }

  /**
   * サーバーコマンドから /farm delete を検知する。
   *
   * @param e コマンドイベント
   */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onServerCommand(final ServerCommandEvent e) {
    maybeHandleFarmDelete("/" + e.getCommand());
  }

  /**
   * /farm delete を検知したら枠を撤去し、自動生成が即再建しないよう抑制する。
   *
   * @param raw コマンド文字列（先頭に / あり）
   */
  private void maybeHandleFarmDelete(final String raw) {
    if (!clearOnFarmDelete || raw == null) {
      return;
    }
    if (!isFarmDeleteCommand(raw)) {
      return;
    }

    // コマンド処理完了を待ってから撤去する。
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              final int removed = clearStoredFrame();
              frameActive = false;
              frameStyleIndex = -1;
              saveState();
              // 削除後のアリーナ署名を記録し、
              // auto-build が即再生成しないよう抑制。
              recordCurrentArenaSig();
              getLogger()
                  .info("[farmframe] /farm delete を検知 → " + "枠を撤去しました（" + removed + " ブロック）。");
            },
            FARM_DELETE_DELAY_TICKS);
  }

  /**
   * コマンド文字列が /farm delete かどうかを判定する。 名前空間付き（plugin:farm delete）にも対応。
   *
   * @param raw コマンド文字列（先頭に / あり）
   * @return /farm delete の場合 true
   */
  private boolean isFarmDeleteCommand(final String raw) {
    String s = raw.trim();
    if (s.startsWith("/")) {
      s = s.substring(1);
    }
    final String[] parts = s.trim().split("\\s+");
    if (parts.length < 2) {
      return false;
    }
    String cmd = parts[0].toLowerCase(Locale.ROOT);
    // 名前空間付き（例: "s2e-farm-pro:farm"）の場合、
    // コロン以降を取得する。
    final int colon = cmd.indexOf(':');
    if (colon >= 0) {
      cmd = cmd.substring(colon + 1);
    }
    return "farm".equals(cmd) && "delete".equalsIgnoreCase(parts[1]);
  }

  /** 現在のアリーナ署名を lastArenaSig に記録する。 auto-build 抑制に使用。 */
  private void recordCurrentArenaSig() {
    final Arena after = readArena();
    if (after != null) {
      lastArenaSig = buildArenaSig(after);
    }
  }

  // ===================== 枠ブロック判定 =====================

  /**
   * ブロックが枠ブロックかどうかを判定する。
   *
   * @param block 判定対象のブロック
   * @return 枠ブロックの場合 true
   */
  private boolean isFrameBlock(final Block block) {
    return isFrameBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
  }

  /**
   * 対象ブロック自身か、その6近傍が枠ブロックなら true。 火を枠の周囲で止めるために使用。
   *
   * @param block 判定対象のブロック
   * @return 枠またはその近傍の場合 true
   */
  private boolean touchesFrame(final Block block) {
    final String w = block.getWorld().getName();
    final int x = block.getX();
    final int y = block.getY();
    final int z = block.getZ();
    return isFrameBlock(w, x, y, z)
        || isFrameBlock(w, x + 1, y, z)
        || isFrameBlock(w, x - 1, y, z)
        || isFrameBlock(w, x, y, z + 1)
        || isFrameBlock(w, x, y, z - 1)
        || isFrameBlock(w, x, y + 1, z)
        || isFrameBlock(w, x, y - 1, z);
  }

  /**
   * その座標が現在の枠（外周リング＋高さ範囲）に属するか。
   *
   * @param world ワールド名
   * @param x X座標
   * @param y Y座標
   * @param z Z座標
   * @return 枠領域に属する場合 true
   */
  private boolean isFrameBlock(final String world, final int x, final int y, final int z) {
    if (!frameActive || frameWorld == null || !frameWorld.equals(world)) {
      return false;
    }
    if (x < fMinX || x > fMaxX || z < fMinZ || z > fMaxZ) {
      return false;
    }
    final boolean edge = (x == fMinX || x == fMaxX || z == fMinZ || z == fMaxZ);
    if (!edge) {
      return false;
    }
    return y >= fBaseY && y <= fBaseY + fPostHeight + 1;
  }

  // ===================== 自動追従 =====================

  /** 自動追従タスクを（再）起動する。 既存のタスクがあれば先に停止する。 */
  private void startFollowTask() {
    stopFollowTask();
    if (!autoFollow) {
      return;
    }
    final long period = (long) autoFollowInterval * TICKS_PER_SECOND;
    followTaskId =
        Bukkit.getScheduler().runTaskTimer(this, this::followTick, period, period).getTaskId();
  }

  /** 自動追従タスクを停止する。 */
  private void stopFollowTask() {
    if (followTaskId != -1) {
      Bukkit.getScheduler().cancelTask(followTaskId);
      followTaskId = -1;
    }
  }

  /**
   * アリーナの状態を監視し、変化があれば枠を再生成する。
   *
   * <ul>
   *   <li>既存の枠がある場合: サイズ/位置変化に追従して作り直す
   *   <li>枠が無く auto-build 有効: 新アリーナ検知で自動生成
   * </ul>
   */
  private void followTick() {
    final Arena arena = readArena();
    if (arena == null) {
      return;
    }
    final String sig = buildArenaSig(arena);
    final boolean changed = !sig.equals(lastArenaSig);
    lastArenaSig = sig;
    if (!changed) {
      return;
    }

    if (frameActive && frameStyleIndex >= 0 && frameStyleIndex < styles.size()) {
      getLogger().info("[farmframe] アリーナ変化を検知 → 枠を作り直します。");
      rebuild(arena, frameStyleIndex);
    } else if (autoBuild) {
      final int style = resolveAutoStyle();
      getLogger().info("[farmframe] アリーナを検知 → " + "枠を自動生成します（style " + (style + 1) + "）。");
      rebuild(arena, style);
    }
  }

  /**
   * アリーナの署名文字列を生成する。変化検知に使用。
   *
   * @param arena アリーナ範囲
   * @return 署名文字列
   */
  private String buildArenaSig(final Arena arena) {
    return arena.world.getName()
        + ":"
        + arena.minX
        + ","
        + arena.maxX
        + ","
        + arena.minZ
        + ","
        + arena.maxZ
        + ","
        + arena.y;
  }

  /**
   * 自動生成に使うスタイルインデックスを決定する。 直前に建てたスタイルがあればそれを優先。
   *
   * @return スタイルインデックス（0始まり）
   */
  private int resolveAutoStyle() {
    if (lastStyleIndex >= 0 && lastStyleIndex < styles.size()) {
      return lastStyleIndex;
    }
    return clampStyleIndex(autoStyleIndex);
  }

  /**
   * スタイルインデックスを有効範囲にクランプする。
   *
   * @param index クランプ対象のインデックス
   * @return 有効範囲内のインデックス
   */
  private int clampStyleIndex(final int index) {
    if (styles.isEmpty()) {
      return 0;
    }
    if (index < 0) {
      return 0;
    }
    return Math.min(index, styles.size() - 1);
  }

  // ===================== 状態の永続化 =====================

  /**
   * state.yml の File オブジェクトを返す。
   *
   * @return state.yml のファイルパス
   */
  private File stateFile() {
    return new File(getDataFolder(), "state.yml");
  }

  /** 枠の現在の状態を state.yml に保存する。 保存失敗時は warning ログを出力し、動作は継続する。 */
  private void saveState() {
    final YamlConfiguration state = new YamlConfiguration();
    state.set("active", frameActive);
    state.set("style-index", frameStyleIndex);
    state.set("last-style-index", lastStyleIndex);
    state.set("world", frameWorld);
    state.set("min-x", fMinX);
    state.set("max-x", fMaxX);
    state.set("min-z", fMinZ);
    state.set("max-z", fMaxZ);
    state.set("base-y", fBaseY);
    state.set("post-height", fPostHeight);

    final List<String> matNames = new ArrayList<>();
    for (final Material m : frameMaterials) {
      matNames.add(m.name());
    }
    state.set("materials", matNames);

    try {
      state.save(stateFile());
    } catch (IOException e) {
      getLogger().warning("state.yml の保存に失敗: " + e);
    }
  }

  /** state.yml から枠の状態を復元する。 ファイルが存在しない場合は何もしない。 */
  private void loadState() {
    final File f = stateFile();
    if (!f.exists()) {
      return;
    }

    final YamlConfiguration state;
    try {
      state = YamlConfiguration.loadConfiguration(f);
    } catch (Exception e) {
      getLogger().warning("state.yml の読み込みに失敗: " + e);
      return;
    }

    frameActive = state.getBoolean("active", false);
    frameStyleIndex = state.getInt("style-index", -1);
    lastStyleIndex = state.getInt("last-style-index", frameStyleIndex);
    frameWorld = state.getString("world");
    fMinX = state.getInt("min-x");
    fMaxX = state.getInt("max-x");
    fMinZ = state.getInt("min-z");
    fMaxZ = state.getInt("max-z");
    fBaseY = state.getInt("base-y");
    fPostHeight = state.getInt("post-height", postHeight);

    frameMaterials.clear();
    for (final String name : state.getStringList("materials")) {
      final Material m = Material.matchMaterial(name);
      if (m != null) {
        frameMaterials.add(m);
      }
    }
  }

  // ===================== タブ補完 =====================

  /**
   * タブ補完候補を返す。
   *
   * @param sender コマンド送信者
   * @param command コマンドオブジェクト
   * @param alias 使用されたエイリアス
   * @param args 現在の引数
   * @return 補完候補リスト
   */
  @Override
  public List<String> onTabComplete(
      final CommandSender sender, final Command command, final String alias, final String[] args) {
    final List<String> out = new ArrayList<>();
    if (args.length == 1) {
      final String prefix = args[0].toLowerCase(Locale.ROOT);
      final List<String> options = new ArrayList<>();
      for (int i = 1; i <= styles.size(); i++) {
        options.add(String.valueOf(i));
      }
      options.add("list");
      options.add("clear");
      options.add("reload");
      for (final String opt : options) {
        if (opt.startsWith(prefix)) {
          out.add(opt);
        }
      }
    }
    return out;
  }
}
