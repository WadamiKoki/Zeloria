package com.zeloria.item;

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
 * アイテム設定（{@code MaterialData/} / {@code ArmorData/} / {@code WeaponData/}）を読み込み、
 * 表示名・説明文（MiniMessage）と PersistentDataContainer（PDC）を適用した
 * {@link ItemStack} を生成するサービス。
 * <p>
 * 各設定 YAML は {@link ItemData} の共通フォーマットを継承し、{@code id} または
 * ファイル名（拡張子なし）で参照できる。{@code /admin reload} 時は {@link #reload()} を
 * 呼ぶことで最新の設定を反映できる。
 * <p>
 * モデル・テクスチャの管理は Paper 1.21.4+ の item_model コンポーネントと PDC に集約する。
 * <ul>
 *     <li>item_model: YAML で {@code item_model} が明示された場合のみ適用する</li>
 *     <li>PDC: {@code zeloria:item_id}（String）を必ず格納し、
 *         item_model 適用時のみ {@code zeloria:item_model}（String）も格納する</li>
 * </ul>
 * item_model 未指定時はコンポーネントを付与しない。これにより Java 版・Bedrock 版の
 * 双方で、リソースパック未導入でも {@code material} 本来のバニラテクスチャが表示され、
 * 紫黒の Missing Texture（バグブロック）化を防ぐ。
 * CustomModelData は使用しない。いずれも内部コンポーネントのみを変更し、
 * 表示名・Lore には影響しない。
 */
public class ItemService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 既定の item_model 名前空間。 */
    public static final String MODEL_NAMESPACE = "zeloria";

    /** PDC キー: アイテム ID。 */
    public static final String KEY_ITEM_ID = "item_id";
    /** PDC キー: アイテム種別（{@link ItemCategory#key()}）。 */
    public static final String KEY_ITEM_CATEGORY = "item_category";
    /** PDC キー: 売却価格。 */
    public static final String KEY_SELL_PRICE = "sell_price";
    /** PDC キー: 1 スロットあたりのスタック上限。 */
    public static final String KEY_MAX_STACK_SIZE = "max_stack_size";
    /** PDC キー: 防具の装着部位。 */
    public static final String KEY_EQUIPMENT_SLOT = "equipment_slot";
    /** PDC キー: 適用した item_model（Geyser / BE 連携用の文字列表現）。 */
    public static final String KEY_ITEM_MODEL = "item_model";
    /** PDC キー: 装着に必要な最低レベル。 */
    public static final String KEY_REQUIRED_LEVEL = "required_level";

    private final Zeloria plugin;
    private final YamlLoader yamlLoader;

    private final List<MaterialData> materials = new CopyOnWriteArrayList<>();
    private final List<ArmorData> armors = new CopyOnWriteArrayList<>();
    private final List<WeaponData> weapons = new CopyOnWriteArrayList<>();

    private final Map<String, MaterialData> materialById = new ConcurrentHashMap<>();
    private final Map<String, ArmorData> armorById = new ConcurrentHashMap<>();
    private final Map<String, WeaponData> weaponById = new ConcurrentHashMap<>();

    private final NamespacedKey itemIdKey;
    private final NamespacedKey itemCategoryKey;
    private final NamespacedKey sellPriceKey;
    private final NamespacedKey maxStackSizeKey;
    private final NamespacedKey equipmentSlotKey;
    private final NamespacedKey itemModelKey;
    private final NamespacedKey requiredLevelKey;
    private final NamespacedKey atkKey;
    private final NamespacedKey defKey;
    private final NamespacedKey hpRegenKey;
    private final NamespacedKey mpRegenKey;
    private final NamespacedKey critRateKey;
    private final NamespacedKey critDmgKey;
    private final NamespacedKey cdrKey;
    private final NamespacedKey moveSpeedKey;
    private final NamespacedKey weaponTypeKey;
    private final NamespacedKey baseDamageKey;
    private final NamespacedKey attackRangeKey;
    private final NamespacedKey attackAngleKey;

    public ItemService(Zeloria plugin, YamlLoader yamlLoader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");

        // 識別子は Geyser / BE 連携でも参照できるよう、常に zeloria 名前空間へ固定する。
        this.itemIdKey = new NamespacedKey(MODEL_NAMESPACE, KEY_ITEM_ID);
        this.itemCategoryKey = new NamespacedKey(MODEL_NAMESPACE, KEY_ITEM_CATEGORY);
        this.sellPriceKey = new NamespacedKey(MODEL_NAMESPACE, KEY_SELL_PRICE);
        this.maxStackSizeKey = new NamespacedKey(MODEL_NAMESPACE, KEY_MAX_STACK_SIZE);
        this.equipmentSlotKey = new NamespacedKey(MODEL_NAMESPACE, KEY_EQUIPMENT_SLOT);
        this.itemModelKey = new NamespacedKey(MODEL_NAMESPACE, KEY_ITEM_MODEL);
        this.requiredLevelKey = new NamespacedKey(plugin, KEY_REQUIRED_LEVEL);
        this.atkKey = new NamespacedKey(plugin, "atk");
        this.defKey = new NamespacedKey(plugin, "def");
        this.hpRegenKey = new NamespacedKey(plugin, "hp_regen");
        this.mpRegenKey = new NamespacedKey(plugin, "mp_regen");
        this.critRateKey = new NamespacedKey(plugin, "crit_rate");
        this.critDmgKey = new NamespacedKey(plugin, "crit_dmg");
        this.cdrKey = new NamespacedKey(plugin, "cdr");
        this.moveSpeedKey = new NamespacedKey(plugin, "move_speed");
        this.weaponTypeKey = new NamespacedKey(plugin, "weapon_type");
        this.baseDamageKey = new NamespacedKey(plugin, "base_damage");
        this.attackRangeKey = new NamespacedKey(plugin, "attack_range");
        this.attackAngleKey = new NamespacedKey(plugin, "attack_angle");

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
        materialById.clear();
        armorById.clear();
        weaponById.clear();

        for (String key : yamlLoader.getKeys()) {
            if (key.startsWith(ItemCategory.MATERIAL.keyPrefix())) {
                loadMaterial(key);
            } else if (key.startsWith(ItemCategory.ARMOR.keyPrefix())) {
                loadArmor(key);
            } else if (key.startsWith(ItemCategory.WEAPON.keyPrefix())) {
                loadWeapon(key);
            }
        }

        plugin.getLogger().info("アイテム設定を読み込みました (Material: " + materials.size()
                + ", Armor: " + armors.size() + ", Weapon: " + weapons.size() + ").");
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
            plugin.getLogger().warning("MaterialData の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
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
            register(armorById, fileNameOf(key), data, key);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("ArmorData の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
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
            // WeaponData/<class>/*.yml のような階層化ファイルでは、
            // 同名の test.yml がクラスごとに存在し得るためファイル名は登録しない。
            if (!isNested(key)) {
                register(weaponById, fileNameOf(key), data, key);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("WeaponData の解析に失敗しました: " + key + " (" + e.getMessage() + ")");
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
     * 生成済み {@link ItemStack} から元のアイテム定義を解決する。
     * <p>
     * PDC の {@code item_category} と {@code item_id} を参照する。
     * どちらかが欠落している場合（旧バージョンで生成されたアイテムなど）は空を返す。
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
     * PDC の {@code required_level} を優先し、旧バージョンで生成されたアイテムなど
     * 未格納の場合はアイテム定義の {@code requiredLevel} を参照する。
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

    /* ------------------------------------------------------------------
     * ItemStack 生成
     * ------------------------------------------------------------------ */

    /**
     * 設定データから個数 1 の {@link ItemStack} を生成する。
     * <p>
     * MiniMessage の表示名・説明文を適用し、item_model コンポーネントと
     * 次の情報を PDC に格納する（表示名・Lore には影響しない）。
     * <ul>
     *     <li>{@code zeloria:item_id} / {@code zeloria:item_model}（Geyser / BE 連携用）</li>
     *     <li>{@code item_category} / {@code sell_price} / {@code max_stack_size}</li>
     *     <li>防具・武器の各種ステータス</li>
     * </ul>
     * CustomModelData は使用しない。
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

        // バニラの攻撃力・防御力表示は独自ステータスと重複するため隠す。
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        // Paper 1.21.4+ の item_model コンポーネントは、YAML で明示された場合のみ適用する。
        // 未指定時に zeloria:<id> を自動付与すると、リソースパック未導入環境で
        // Missing Texture（紫黒ブロック）になるため、その場合はコンポーネントを付けない。
        // PDC の zeloria:item_id は item_model の有無にかかわらず常に付与する。
        NamespacedKey itemModel = resolveItemModel(data);
        if (itemModel != null) {
            meta.setItemModel(itemModel);
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemIdKey, PersistentDataType.STRING, data.id());
        if (itemModel != null) {
            pdc.set(itemModelKey, PersistentDataType.STRING, itemModel.toString());
        }
        pdc.set(itemCategoryKey, PersistentDataType.STRING, data.category().key());
        pdc.set(sellPriceKey, PersistentDataType.LONG, data.sellPrice());
        pdc.set(maxStackSizeKey, PersistentDataType.INTEGER, data.maxStackSize());

        applyTypeData(pdc, data);

        // Minecraft の max_stack_size コンポーネントは 1〜99 のみ指定可能。
        // それを超える値（例: 9999）は PDC の max_stack_size で管理する。
        int stackSize = data.maxStackSize();
        if (stackSize >= 1 && stackSize <= 99) {
            meta.setMaxStackSize(stackSize);
        }

        stack.setItemMeta(meta);
        stack.setAmount(1);
        return stack;
    }

    /**
     * アイテムに適用する item_model の {@link NamespacedKey} を解決する。
     * <p>
     * YAML に {@code item_model}（例: {@code minecraft:diamond_sword}）が明示されている
     * 場合のみそのキーを返す。未指定・不正な場合は {@code null} を返し、
     * item_model コンポーネントを付与しない（{@code material} のバニラ表示を使用する）。
     * <p>
     * {@code zeloria:<id>} の自動付与は行わない。リソースパック未導入の Java 版 /
     * Bedrock 版で Missing Texture（紫黒ブロック）になることを防ぐため。
     *
     * @param data アイテム定義
     * @return item_model キー。明示指定が無い／不正な場合は {@code null}
     */
    public NamespacedKey resolveItemModel(ItemData data) {
        Objects.requireNonNull(data, "data");

        Optional<String> explicit = data.itemModel();
        if (explicit.isEmpty()) {
            return null;
        }

        NamespacedKey parsed = parseModelKey(explicit.get());
        if (parsed == null) {
            plugin.getLogger().warning(
                    "item_model の指定が不正なため item_model を適用しません: id=" + data.id()
                            + ", item_model=" + explicit.get());
        }
        return parsed;
    }

    /**
     * {@code zeloria:<id>} 形式の既定 item_model キーを生成する。
     * <p>
     * ID に名前空間として不正な文字が含まれる場合は {@code _} へ置換する。
     *
     * @param id アイテム ID
     * @return 既定の item_model キー
     * @deprecated item_model 未指定時に {@code zeloria:<id>} を自動適用すると、
     *             リソースパック未導入環境で Missing Texture（紫黒ブロック）になるため、
     *             {@link #createItem(ItemData)} は本メソッドを使用しない。
     *             YAML の {@code item_model} で明示指定する運用へ移行すること。
     */
    @Deprecated
    public static NamespacedKey defaultItemModel(String id) {
        return new NamespacedKey(MODEL_NAMESPACE, sanitizeKey(id));
    }

    /**
     * 明示指定された item_model 文字列を {@link NamespacedKey} へ解決する。
     * <p>
     * {@code minecraft:diamond_sword} のような名前空間付きのほか、
     * 名前空間を省略した {@code diamond_sword} も受け付ける（{@code minecraft} 扱い）。
     *
     * @param raw 設定値
     * @return 解決されたキー。不正な場合は {@code null}
     */
    private static NamespacedKey parseModelKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NamespacedKey.fromString(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * item_model のキー部分として使用できる文字列へ正規化する。
     */
    private static String sanitizeKey(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/';
            result.append(valid ? c : '_');
        }
        return result.isEmpty() ? "item" : result.toString();
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
            pdc.set(baseDamageKey, PersistentDataType.DOUBLE, weapon.baseDamage());
            pdc.set(attackRangeKey, PersistentDataType.DOUBLE, weapon.attackRange());
            pdc.set(attackAngleKey, PersistentDataType.DOUBLE, weapon.attackAngle());
            pdc.set(requiredLevelKey, PersistentDataType.INTEGER, weapon.requiredLevel());
        }
    }

    /**
     * アイテムのスタック上限を返す。
     * <p>
     * 生成時に PDC へ格納した {@code max_stack_size} を優先し、無い場合は
     * ItemStack / Material の既定値を使用する。
     *
     * @param stack 判定対象
     * @return 1〜9999 のスタック上限
     */
    public int maxStackSize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 1;
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Integer value = meta.getPersistentDataContainer().get(maxStackSizeKey, PersistentDataType.INTEGER);
            if (value != null) {
                return Math.max(MaterialData.MIN_STACK_SIZE,
                        Math.min(MaterialData.MAX_STACK_SIZE, value));
            }
        }
        return Math.max(1, stack.getMaxStackSize());
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
     * ItemMeta に設定された item_model コンポーネントを取得する。
     *
     * @param stack 判定対象
     * @return item_model キー。未設定の場合は空
     */
    public Optional<NamespacedKey> getItemModel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(meta.getItemModel());
    }

    /**
     * PDC に格納された item_model の文字列表現を取得する。
     * <p>
     * Geyser / BE 連携で参照する想定の値（例: {@code zeloria:iron_ore}）。
     *
     * @param stack 判定対象
     * @return item_model の文字列表現。未格納の場合は空
     */
    public Optional<String> getStoredItemModel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                meta.getPersistentDataContainer().get(itemModelKey, PersistentDataType.STRING));
    }

    /**
     * 名前・説明文の MiniMessage を解決する。説明文はバニラ同様に斜体を無効化する。
     */
    private static Component component(String miniMessage) {
        return MINI.deserialize("<!italic>" + (miniMessage == null ? "" : miniMessage));
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
            addStat(lines, "基礎ダメージ", weapon.baseDamage());
            addStat(lines, "攻撃範囲", weapon.attackRange());
            addStat(lines, "攻撃角度", weapon.attackAngle());
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

    private static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        String fileName = slash < 0 ? key : key.substring(slash + 1);
        return fileName.toLowerCase(Locale.ROOT).endsWith(".yml")
                ? fileName.substring(0, fileName.length() - ".yml".length())
                : fileName;
    }

    /**
     * {@code WeaponData/<class>/<name>.yml} のように 2 階層以上か判定する。
     */
    private static boolean isNested(String key) {
        int first = key.indexOf('/');
        return first >= 0 && key.indexOf('/', first + 1) >= 0;
    }
}
