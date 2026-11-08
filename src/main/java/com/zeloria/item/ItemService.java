package com.zeloria.item;

import com.zeloria.core.TextComponents;
import com.zeloria.core.Zeloria;
import com.zeloria.core.YamlLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * アイテム設定（{@code ItemData/material/} / {@code ItemData/armor/} / {@code ItemData/weapon/} /
 * {@code ItemData/tool/}）を読み込み、
 * 表示名・説明文（MiniMessage）と PersistentDataContainer（PDC）を適用した
 * {@link ItemStack} を生成するサービス。
 * <p>
 * 各設定 YAML は {@link ItemData} の共通フォーマットを継承し、{@code id} または
 * ファイル名（拡張子なし）で参照できる。{@code /admin reload} 時は {@link #reload()} を
 * 呼ぶことで最新の設定を反映できる。
 * <p>
 * 見た目は {@code material} のバニラテクスチャをそのまま使用する。リソースパック用の
 * CustomModelData や item_model コンポーネントは付与しない。
 * PDC には {@code zeloria:item_id}（String）などの識別情報のみを格納する。
 */
public class ItemService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** PDC の名前空間。 */
    public static final String MODEL_NAMESPACE = "zeloria";

    /** PDC キー: アイテム ID。 */
    public static final String KEY_ITEM_ID = "item_id";
    /** PDC キー: アイテム種別（{@link ItemCategory#key()}）。 */
    public static final String KEY_ITEM_CATEGORY = "item_category";
    /** PDC キー: 売却価格。 */
    public static final String KEY_SELL_PRICE = "sell_price";
    /** PDC キー: 防具の装着部位。 */
    public static final String KEY_EQUIPMENT_SLOT = "equipment_slot";
    /** PDC キー: 装着に必要な最低レベル。 */
    public static final String KEY_REQUIRED_LEVEL = "required_level";
    /** PDC キー: 採取ツールのギャザリング種別。 */
    public static final String KEY_GATHERING_TYPE = "gathering_type";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    private final List<MaterialData> materials = new CopyOnWriteArrayList<>();
    private final List<ArmorData> armors = new CopyOnWriteArrayList<>();
    private final List<WeaponData> weapons = new CopyOnWriteArrayList<>();
    private final List<ToolData> tools = new CopyOnWriteArrayList<>();

    private final Map<String, MaterialData> materialById = new ConcurrentHashMap<>();
    private final Map<String, ArmorData> armorById = new ConcurrentHashMap<>();
    private final Map<String, WeaponData> weaponById = new ConcurrentHashMap<>();
    private final Map<String, ToolData> toolById = new ConcurrentHashMap<>();

    private final NamespacedKey itemIdKey;
    private final NamespacedKey itemCategoryKey;
    private final NamespacedKey sellPriceKey;
    private final NamespacedKey equipmentSlotKey;
    private final NamespacedKey requiredLevelKey;
    private final NamespacedKey gatheringTypeKey;
    private final NamespacedKey atkKey;
    private final NamespacedKey defKey;
    private final NamespacedKey hpRegenKey;
    private final NamespacedKey mpRegenKey;
    private final NamespacedKey critRateKey;
    private final NamespacedKey critDmgKey;
    private final NamespacedKey cdrKey;
    private final NamespacedKey moveSpeedKey;
    private final NamespacedKey weaponTypeKey;
    private final NamespacedKey attackSpeedTicksKey;
    private final NamespacedKey attackRangeKey;
    private final NamespacedKey attackAngleKey;
    private final NamespacedKey attackParticleKey;
    private final NamespacedKey attackSoundKey;

    public ItemService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");

        // 識別子は Geyser / BE 連携でも参照できるよう、常に zeloria 名前空間へ固定する。
        this.itemIdKey = new NamespacedKey(MODEL_NAMESPACE, KEY_ITEM_ID);
        this.itemCategoryKey = new NamespacedKey(MODEL_NAMESPACE, KEY_ITEM_CATEGORY);
        this.sellPriceKey = new NamespacedKey(MODEL_NAMESPACE, KEY_SELL_PRICE);
        this.equipmentSlotKey = new NamespacedKey(MODEL_NAMESPACE, KEY_EQUIPMENT_SLOT);
        this.requiredLevelKey = new NamespacedKey(plugin, KEY_REQUIRED_LEVEL);
        this.gatheringTypeKey = new NamespacedKey(plugin, KEY_GATHERING_TYPE);
        this.atkKey = new NamespacedKey(plugin, "atk");
        this.defKey = new NamespacedKey(plugin, "def");
        this.hpRegenKey = new NamespacedKey(plugin, "hp_regen");
        this.mpRegenKey = new NamespacedKey(plugin, "mp_regen");
        this.critRateKey = new NamespacedKey(plugin, "crit_rate");
        this.critDmgKey = new NamespacedKey(plugin, "crit_dmg");
        this.cdrKey = new NamespacedKey(plugin, "cdr");
        this.moveSpeedKey = new NamespacedKey(plugin, "move_speed");
        this.weaponTypeKey = new NamespacedKey(plugin, "weapon_type");
        this.attackSpeedTicksKey = new NamespacedKey(plugin, "attack_speed_ticks");
        this.attackRangeKey = new NamespacedKey(plugin, "attack_range");
        this.attackAngleKey = new NamespacedKey(plugin, "attack_angle");
        this.attackParticleKey = new NamespacedKey(plugin, "attack_particle");
        this.attackSoundKey = new NamespacedKey(plugin, "attack_sound");

        reload();
    }

    /* ------------------------------------------------------------------
     * 設定の読み込み
     * ------------------------------------------------------------------ */

    /**
     * アイテム設定を再読み込みし、キャッシュを置き換える。
     */
    public synchronized void reload() {
        materials.clear();
        armors.clear();
        weapons.clear();
        tools.clear();
        materialById.clear();
        armorById.clear();
        weaponById.clear();
        toolById.clear();

        for (String key : yamlLoader.getKeys()) {
            if (key.startsWith(ItemCategory.MATERIAL.keyPrefix())) {
                loadMaterial(key);
            } else if (key.startsWith(ItemCategory.ARMOR.keyPrefix())) {
                loadArmor(key);
            } else if (key.startsWith(ItemCategory.WEAPON.keyPrefix())) {
                loadWeapon(key);
            } else if (key.startsWith(ItemCategory.TOOL.keyPrefix())) {
                loadTool(key);
            }
        }

        plugin.getLogger().info("アイテム設定を読み込みました (Material: " + materials.size()
                + ", Armor: " + armors.size() + ", Weapon: " + weapons.size()
                + ", Tool: " + tools.size() + ").");
    }

    private void loadMaterial(String key) {
        FileConfiguration config = yamlLoader.getConfig(key);
        if (config == null) {
            return;
        }
        try {
            MaterialData data = MaterialData.fromConfig(key, config);
            materials.add(data);
            register(materialById, data.id(), data, key);
            register(materialById, fileNameOf(key), data, key);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("ItemData/material の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
        }
    }

    private void loadArmor(String key) {
        FileConfiguration config = yamlLoader.getConfig(key);
        if (config == null) {
            return;
        }
        try {
            ArmorData data = ArmorData.fromConfig(key, config);
            armors.add(data);
            register(armorById, data.id(), data, key);
            // ItemData/armor/<slot>/*.yml のような階層化ファイルでは、
            // 同名の test.yml が部位ごとに存在し得るためファイル名は登録しない。
            if (!isNested(key)) {
                register(armorById, fileNameOf(key), data, key);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("ItemData/armor の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
        }
    }

    private void loadWeapon(String key) {
        FileConfiguration config = yamlLoader.getConfig(key);
        if (config == null) {
            return;
        }
        try {
            WeaponData data = WeaponData.fromConfig(key, config);
            weapons.add(data);
            register(weaponById, data.id(), data, key);
            // ItemData/weapon/<class>/*.yml のような階層化ファイルでは、
            // 同名の test.yml がクラスごとに存在し得るためファイル名は登録しない。
            if (!isNested(key)) {
                register(weaponById, fileNameOf(key), data, key);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("ItemData/weapon の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
        }
    }

    private void loadTool(String key) {
        FileConfiguration config = yamlLoader.getConfig(key);
        if (config == null) {
            return;
        }
        try {
            ToolData data = ToolData.fromConfig(key, config);
            tools.add(data);
            register(toolById, data.id(), data, key);
            // ItemData/tool/<種別>/*.yml では同名の test.yml が種別ごとに存在し得るため、
            // ファイル名は登録しない。
        } catch (RuntimeException e) {
            plugin.getLogger().warning("ItemData/tool の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
        }
    }

    private static <T extends ItemData> void register(
            Map<String, T> target, String key, T data, String sourceKey) {
        if (key == null || key.isBlank()) {
            return;
        }
        T previous = target.putIfAbsent(key.toLowerCase(Locale.ROOT), data);
        if (previous != null && previous != data) {
            // 重複は先勝ち。運用者が気づけるよう警告のみ行う。
            System.getLogger(ItemService.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "アイテム ID が重複しています: ''{0}'' ({1})", key, sourceKey);
        }
    }

    /* ------------------------------------------------------------------
     * 検索
     * ------------------------------------------------------------------ */

    /**
     * 種別と ID（またはファイル名）からアイテム定義を取得する（大文字小文字は区別しない）。
     *
     * @param category アイテム種別
     * @param id       アイテム ID
     * @return アイテム定義。見つからない場合は空
     */
    public Optional<ItemData> find(ItemCategory category, String id) {
        if (category == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        return switch (category) {
            case MATERIAL -> Optional.ofNullable(materialById.get(key));
            case ARMOR -> Optional.ofNullable(armorById.get(key));
            case WEAPON -> Optional.ofNullable(weaponById.get(key));
            case TOOL -> Optional.ofNullable(toolById.get(key));
        };
    }

    /**
     * 素材定義を取得する。
     */
    public Optional<MaterialData> findMaterial(String id) {
        return find(ItemCategory.MATERIAL, id).map(MaterialData.class::cast);
    }

    /**
     * 防具定義を取得する。
     */
    public Optional<ArmorData> findArmor(String id) {
        return find(ItemCategory.ARMOR, id).map(ArmorData.class::cast);
    }

    /**
     * 武器定義を取得する。
     */
    public Optional<WeaponData> findWeapon(String id) {
        return find(ItemCategory.WEAPON, id).map(WeaponData.class::cast);
    }

    /**
     * 採取ツール定義を取得する。
     */
    public Optional<ToolData> findTool(String id) {
        return find(ItemCategory.TOOL, id).map(ToolData.class::cast);
    }

    /**
     * 生成済み {@link ItemStack} から元のアイテム定義を解決する。
     * <p>
     * PDC の {@code item_category} と {@code item_id} を参照する。
     * どちらかが欠落している場合は空を返す。
     *
     * @param stack 判定対象
     * @return アイテム定義。プラグイン製アイテムでない場合は空
     */
    public Optional<ItemData> findItem(ItemStack stack) {
        Optional<String> id = getItemId(stack);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        ItemCategory category = getItemCategory(stack).orElse(null);
        if (category != null) {
            Optional<ItemData> found = find(category, id.get());
            if (found.isPresent()) {
                return found;
            }
        }
        for (ItemCategory candidate : ItemCategory.values()) {
            Optional<ItemData> found = find(candidate, id.get());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * PDC に格納されたアイテム種別を取得する。
     *
     * @param stack 判定対象
     * @return アイテム種別。未格納・不正な場合は空
     */
    public Optional<ItemCategory> getItemCategory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String raw = meta.getPersistentDataContainer().get(itemCategoryKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ItemCategory.fromKey(raw));
    }

    /**
     * 生成済みアイテムに格納された必要レベルを取得する。
     * <p>
     * PDC の {@code required_level} を優先し、未格納の場合は
     * アイテム定義の {@code requiredLevel} を参照する。
     *
     * @param stack 判定対象
     * @return 必要レベル（0 は制限なし）
     */
    public int getRequiredLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Integer stored = meta.getPersistentDataContainer()
                    .get(requiredLevelKey, PersistentDataType.INTEGER);
            if (stored != null) {
                return Math.max(0, stored);
            }
        }
        ItemData data = findItem(stack).orElse(null);
        if (data instanceof ArmorData armor) {
            return armor.requiredLevel();
        }
        if (data instanceof WeaponData weapon) {
            return weapon.requiredLevel();
        }
        if (data instanceof ToolData tool) {
            return tool.requiredLevel();
        }
        return 0;
    }


    /**
     * 読み込み済みの ID 一覧を取得する（タブ補完用）。
     */
    public List<String> getIds(ItemCategory category) {
        if (category == null) {
            return List.of();
        }
        return switch (category) {
            case MATERIAL -> materials.stream().map(MaterialData::id).distinct().sorted().toList();
            case ARMOR -> armors.stream().map(ArmorData::id).distinct().sorted().toList();
            case WEAPON -> weapons.stream().map(WeaponData::id).distinct().sorted().toList();
            case TOOL -> tools.stream().map(ToolData::id).distinct().sorted().toList();
        };
    }

    public List<MaterialData> getMaterials() {
        return List.copyOf(materials);
    }

    public List<ArmorData> getArmors() {
        return List.copyOf(armors);
    }

    public List<WeaponData> getWeapons() {
        return List.copyOf(weapons);
    }

    public List<ToolData> getTools() {
        return List.copyOf(tools);
    }

    /* ------------------------------------------------------------------
     * ItemStack 生成
     * ------------------------------------------------------------------ */

    /**
     * 設定データから個数 1 の {@link ItemStack} を生成する。
     * <p>
     * MiniMessage の表示名・説明文を適用し、次の情報を PDC に格納する
     * （表示名・Lore には影響しない）。
     * <ul>
     *     <li>{@code zeloria:item_id}</li>
     *     <li>{@code item_category} / {@code sell_price}</li>
     *     <li>防具・武器・採取ツールの各種ステータス</li>
     * </ul>
     * リソースパック用のモデルは付与しない。
     *
     * @param data アイテム定義
     * @return 生成した ItemStack（個数 1）
     */
    public ItemStack createItem(ItemData data) {
        Objects.requireNonNull(data, "data");

        ItemStack stack = new ItemStack(data.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            stack.setAmount(1);
            return stack;
        }

        meta.displayName(component(data.name()));

        List<Component> lore = new ArrayList<>();
        for (String line : data.lore()) {
            lore.add(component(line));
        }
        appendStats(data, lore);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }

        // すべてのカスタムアイテムを耐久無限にし、ツールチップの属性・Unbreakable 表示を隠す。
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemIdKey, PersistentDataType.STRING, data.id());
        pdc.set(itemCategoryKey, PersistentDataType.STRING, data.category().key());
        pdc.set(sellPriceKey, PersistentDataType.LONG, data.sellPrice());

        applyTypeData(pdc, data);

        stack.setItemMeta(meta);
        stack.setAmount(1);
        return stack;
    }

    private void applyTypeData(PersistentDataContainer pdc, ItemData data) {
        if (data instanceof ArmorData armor) {
            pdc.set(equipmentSlotKey, PersistentDataType.STRING, armor.equipmentSlot().name());
            pdc.set(atkKey, PersistentDataType.DOUBLE, armor.atk());
            pdc.set(defKey, PersistentDataType.DOUBLE, armor.def());
            pdc.set(hpRegenKey, PersistentDataType.DOUBLE, armor.hpRegen());
            pdc.set(mpRegenKey, PersistentDataType.DOUBLE, armor.mpRegen());
            pdc.set(critRateKey, PersistentDataType.DOUBLE, armor.critRate());
            pdc.set(critDmgKey, PersistentDataType.DOUBLE, armor.critDmg());
            pdc.set(cdrKey, PersistentDataType.DOUBLE, armor.cdr());
            pdc.set(moveSpeedKey, PersistentDataType.DOUBLE, armor.moveSpeed());
            pdc.set(requiredLevelKey, PersistentDataType.INTEGER, armor.requiredLevel());
        } else if (data instanceof WeaponData weapon) {
            pdc.set(weaponTypeKey, PersistentDataType.STRING, String.join(",", weapon.weaponTypes()));
            pdc.set(attackSpeedTicksKey, PersistentDataType.INTEGER, weapon.attackSpeedTicks());
            pdc.set(attackRangeKey, PersistentDataType.DOUBLE, weapon.attackRange());
            pdc.set(attackAngleKey, PersistentDataType.DOUBLE, weapon.attackAngle());
            pdc.set(attackParticleKey, PersistentDataType.STRING, weapon.attackParticle());
            pdc.set(attackSoundKey, PersistentDataType.STRING, weapon.attackSound());
            pdc.set(requiredLevelKey, PersistentDataType.INTEGER, weapon.requiredLevel());
        } else if (data instanceof ToolData tool) {
            pdc.set(gatheringTypeKey, PersistentDataType.STRING, tool.gatheringType().key());
            pdc.set(requiredLevelKey, PersistentDataType.INTEGER, tool.requiredLevel());
        }
    }

    /**
     * PDC に格納されたアイテム ID を取得する。
     *
     * @param stack 判定対象
     * @return アイテム ID。プラグインが生成したアイテムでない場合は空
     */
    public Optional<String> getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING));
    }

    /**
     * 名前・説明文の MiniMessage を解決する。説明文はバニラ同様に斜体を無効化する。
     */
    private static Component component(String miniMessage) {
        return TextComponents.noItalic(MINI.deserialize(
                "<!italic>" + (miniMessage == null ? "" : miniMessage)));
    }

    /**
     * 防具・武器のステータスを説明文の末尾へ追記する。
     */
    private static void appendStats(ItemData data, List<Component> lore) {
        List<String> lines = new ArrayList<>();
        ItemStatBonus bonus = ItemStatBonus.NONE;

        if (data instanceof ArmorData armor) {
            bonus = armor.statBonus();
        } else if (data instanceof WeaponData weapon) {
            bonus = weapon.statBonus();
            lines.add("<gray>武器種別: <white>" + String.join(", ", weapon.weaponTypes()) + "</white></gray>");
            lines.add("<gray>攻撃速度: <white>" + seconds(weapon.attackSpeedTicks()) + "秒</white></gray>");
            addStat(lines, "攻撃範囲", weapon.attackRange());
            addStat(lines, "攻撃角度", weapon.attackAngle());
        } else if (data instanceof ToolData tool) {
            lore.add(Component.empty());
            lore.add(component("<red>必要採取レベル: <white>" + tool.requiredLevel() + "</white></red>"));
            return;
        } else {
            return;
        }

        addStat(lines, "最大HP", bonus.maxHealth());
        addStat(lines, "最大MP", bonus.maxMana());
        addStat(lines, "攻撃力", bonus.attackPower());
        addStat(lines, "防御力1", bonus.defense1());
        addStat(lines, "防御力2", bonus.defense2());
        addStat(lines, "会心率", bonus.criticalRate());
        addStat(lines, "会心ダメージ", bonus.criticalDamage());
        addStat(lines, "命中率", bonus.accuracy());
        addStat(lines, "回避率", bonus.evasion());
        addStat(lines, "回避CT短縮", bonus.dodgeCooldownReduction());
        addStat(lines, "スキルCT短縮", bonus.cooldownReduction());
        addStat(lines, "移動速度", bonus.moveSpeed());
        addStat(lines, "HP回復", bonus.healthRegenFlat());
        addStat(lines, "HP回復率", bonus.healthRegenPercent());
        addStat(lines, "MP回復", bonus.manaRegenFlat());
        addStat(lines, "MP回復率", bonus.manaRegenPercent());
        addStat(lines, "防御貫通", bonus.armorPenetration());
        addStat(lines, "吸血", bonus.lifesteal());

        if (bonus.requiredLevel() > 0) {
            lines.add("<red>必要レベル: <white>" + bonus.requiredLevel() + "</white></red>");
        }

        if (lines.isEmpty()) {
            return;
        }
        lore.add(Component.empty());
        for (String line : lines) {
            lore.add(component(line));
        }
    }

    private static void addStat(List<String> lines, String label, double value) {
        if (value == 0.0) {
            return;
        }
        String sign = value > 0.0 ? "+" : "";
        lines.add("<gray>" + label + ": <white>" + sign + number(value) + "</white></gray>");
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** tick 数を秒表示へ変換する（20 tick = 1 秒）。 */
    private static String seconds(int ticks) {
        return number(Math.max(1, ticks) / 20.0);
    }

    private static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        String fileName = slash < 0 ? key : key.substring(slash + 1);
        return fileName.toLowerCase(Locale.ROOT).endsWith(".yml")
                ? fileName.substring(0, fileName.length() - ".yml".length())
                : fileName;
    }

    /**
     * {@code ItemData/weapon/<class>/<name>.yml} や
     * {@code ItemData/armor/<slot>/<name>.yml} のように 2 階層以上か判定する。
     */
    private static boolean isNested(String key) {
        int first = key.indexOf('/');
        return first >= 0 && key.indexOf('/', first + 1) >= 0;
    }
}
