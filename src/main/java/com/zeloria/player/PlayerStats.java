package com.zeloria.player;

/**
 * プレイヤーの RPG ステータスを保持するモデルクラス。
 * <p>
 * 値はすべて可変で、{@link com.zeloria.player.PlayerData} が 1 プレイヤーにつき
 * 1 インスタンスを保持する。装備補正・スキル・ダメージ計算は本クラスの責務ではなく、
 * 現時点では各フィールドを保持するだけに留める。
 * <p>
 * 入力値のうち次の項目は仕様に従ってクランプする。
 * <ul>
 *     <li>{@code criticalRate} / {@code accuracy} / {@code evasion}: 0〜100</li>
 *     <li>{@code cooldownReduction} / {@code dodgeCooldownReduction}: 0.0〜0.5（上限 50%）</li>
 * </ul>
 * リジェネ関連（{@code healthRegenFlat} / {@code healthRegenPercent} /
 * {@code manaRegenFlat} / {@code manaRegenPercent}）は、毒やデバフを表現できるよう
 * <strong>マイナス値を許可</strong>し、クランプしない。
 * <p>
 * 最大 HP / 最大 MP は現在値の上限として機能するため、上限を下げた場合は
 * 現在値も自動的に追従する。
 * <p>
 * 値の読み書きはメインスレッド（イベント・タスク）から行われるが、
 * 非同期保存と競合しないよう各アクセサは {@code synchronized} で保護している。
 */
public class PlayerStats {

    /** パーセント系ステータスの下限。 */
    public static final double MIN_PERCENT = 0.0;

    /** パーセント系ステータスの上限。 */
    public static final double MAX_PERCENT = 100.0;

    /** 短縮率ステータスの下限。 */
    public static final double MIN_REDUCTION = 0.0;

    /** 短縮率ステータスの上限（50%）。 */
    public static final double MAX_REDUCTION = 0.5;

    /** 最大 HP の下限（0 以下にすると死亡判定と衝突するため 1 以上を保証する）。 */
    public static final double MIN_MAX_HEALTH = 1.0;

    /** 既定の最大 HP（Minecraft のデフォルト体力 20）。 */
    public static final double DEFAULT_MAX_HEALTH = 20.0;

    /** 既定の最大 MP（MP 基盤が未実装のため 0）。 */
    public static final double DEFAULT_MAX_MANA = 0.0;

    /** 既定の攻撃力。 */
    public static final double DEFAULT_ATTACK_POWER = 1.0;

    /** 既定の防御力（第 1 段階: バフ・スキル用）。 */
    public static final double DEFAULT_DEFENSE1 = 0.0;

    /** 既定の防御力（第 2 段階: 防具用）。 */
    public static final double DEFAULT_DEFENSE2 = 0.0;

    /** 旧 API 互換の防御力既定値（防具防御力と同値）。 */
    public static final double DEFAULT_DEFENSE = DEFAULT_DEFENSE2;

    /** 既定のクリティカル率（%）。 */
    public static final double DEFAULT_CRITICAL_RATE = 5.0;

    /** 既定のクリティカルダメージ（% 加算。50.0 = +50%）。 */
    public static final double DEFAULT_CRITICAL_DAMAGE = 50.0;

    /** 既定の命中率（%）。 */
    public static final double DEFAULT_ACCURACY = 90.0;

    /** 既定の回避率（%）。 */
    public static final double DEFAULT_EVASION = 5.0;

    /** 既定の回避クールダウン短縮率。 */
    public static final double DEFAULT_DODGE_COOLDOWN_REDUCTION = 0.0;

    /** 既定のスキルクールタイム短縮率。 */
    public static final double DEFAULT_COOLDOWN_REDUCTION = 0.0;

    /** 既定の移動速度（Minecraft の WalkSpeed デフォルト値）。 */
    public static final double DEFAULT_MOVE_SPEED = 0.2;

    /** 既定のヘルス固定リジェネ量。 */
    public static final double DEFAULT_HEALTH_REGEN_FLAT = 0.0;

    /** 既定のヘルス割合リジェネ。 */
    public static final double DEFAULT_HEALTH_REGEN_PERCENT = 0.0;

    /** 既定のマナ固定リジェネ量。 */
    public static final double DEFAULT_MANA_REGEN_FLAT = 0.0;

    /** 既定のマナ割合リジェネ。 */
    public static final double DEFAULT_MANA_REGEN_PERCENT = 0.0;

    /** 既定のレベル（新規プレイヤーは 0 から開始する）。 */
    public static final int DEFAULT_LEVEL = 0;

    /** 既定の経験値。 */
    public static final long DEFAULT_EXP = 0L;

    /** レベル。 */
    private int level = DEFAULT_LEVEL;

    /** 現在レベルで保持している経験値。 */
    private long exp = DEFAULT_EXP;

    /**
     * 装備などによる一時的な補正値。
     * <p>
     * DB へ保存する基礎値（各フィールド）とは分離して保持する。ログイン時は
     * 装備補正を適用する前の状態が基礎値のため、そのまま永続化できる。
     */
    private Bonuses bonuses = Bonuses.NONE;

    private double maxHealth = DEFAULT_MAX_HEALTH;
    private double currentHealth = DEFAULT_MAX_HEALTH;

    private double maxMana = DEFAULT_MAX_MANA;
    private double currentMana = DEFAULT_MAX_MANA;

    private double attackPower = DEFAULT_ATTACK_POWER;

    /** 第 1 段階の防御力（バフ・スキル用。防御貫通の影響を受けない）。 */
    private double defense1 = DEFAULT_DEFENSE1;

    /** 第 2 段階の防御力（防具用。防御貫通の影響を受ける）。 */
    private double defense2 = DEFAULT_DEFENSE2;

    private double criticalRate = DEFAULT_CRITICAL_RATE;
    private double criticalDamage = DEFAULT_CRITICAL_DAMAGE;

    private double accuracy = DEFAULT_ACCURACY;
    private double evasion = DEFAULT_EVASION;
    private double dodgeCooldownReduction = DEFAULT_DODGE_COOLDOWN_REDUCTION;

    private double cooldownReduction = DEFAULT_COOLDOWN_REDUCTION;

    private double moveSpeed = DEFAULT_MOVE_SPEED;

    private double healthRegenFlat = DEFAULT_HEALTH_REGEN_FLAT;
    private double healthRegenPercent = DEFAULT_HEALTH_REGEN_PERCENT;
    private double manaRegenFlat = DEFAULT_MANA_REGEN_FLAT;
    private double manaRegenPercent = DEFAULT_MANA_REGEN_PERCENT;

    /**
     * 防御貫通率（0.0〜1.0）の基礎値。
     * <p>
     * クラス基礎ステータスから設定される。装備補正は {@link #bonuses} 側で加算する。
     */
    private double armorPenetration = 0.0;

    /**
     * 吸血率（0.0〜1.0）の基礎値。
     * <p>
     * クラス基礎ステータスから設定される。装備補正は {@link #bonuses} 側で加算する。
     */
    private double lifesteal = 0.0;

    /**
     * 現在の HP / MP を最大値まで回復する（リスポーン時などに使用）。
     */
    public synchronized void restoreVitals() {
        this.currentHealth = getMaxHealth();
        this.currentMana = getMaxMana();
    }

    /* ------------------------------------------------------------------
     * レベル・経験値
     * ------------------------------------------------------------------ */

    public synchronized int getLevel() {
        return level;
    }

    /**
     * レベルを設定する（0 未満は 0）。
     *
     * @param level レベル
     */
    public synchronized void setLevel(int level) {
        this.level = Math.max(0, level);
    }

    public synchronized long getExp() {
        return exp;
    }

    /**
     * 現在レベルで保持している経験値を設定する（0 未満は 0）。
     *
     * @param exp 経験値
     */
    public synchronized void setExp(long exp) {
        this.exp = Math.max(0L, exp);
    }

    /* ------------------------------------------------------------------
     * 装備補正
     * ------------------------------------------------------------------ */

    /**
     * 装備などによる補正値を取得する。
     *
     * @return 現在適用中の補正値（未適用の場合は {@link Bonuses#NONE}）
     */
    public synchronized Bonuses getBonuses() {
        return bonuses;
    }

    /**
     * 装備などによる補正値を適用する。
     * <p>
     * 基礎値は変更せず、取得メソッドのみが基礎値と補正値を合算して返す。
     * 装備変更時は毎回このメソッドで補正値を差し替える。
     *
     * @param bonuses 補正値（{@code null} は補正なし）
     */
    public synchronized void applyBonuses(Bonuses bonuses) {
        this.bonuses = bonuses == null ? Bonuses.NONE : bonuses;
    }

    /**
     * 装備補正を解除する。
     */
    public synchronized void clearBonuses() {
        this.bonuses = Bonuses.NONE;
    }

    /* ------------------------------------------------------------------
     * 基本ステータス（取得値は基礎値 + 装備補正）
     * ------------------------------------------------------------------ */

    public synchronized double getMaxHealth() {
        return Math.max(MIN_MAX_HEALTH, maxHealth + bonuses.maxHealth());
    }

    /**
     * 最大 HP の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない最大 HP
     */
    public synchronized double getBaseMaxHealth() {
        return maxHealth;
    }

    /**
     * 最大 HP を設定する。
     * <p>
     * 1 未満は 1 に引き上げ、現在 HP が上限を超えている場合は上限へ揃える。
     *
     * @param maxHealth 最大 HP
     */
    public synchronized void setMaxHealth(double maxHealth) {
        this.maxHealth = Math.max(MIN_MAX_HEALTH, maxHealth);
        this.currentHealth = clamp(this.currentHealth, 0.0, getMaxHealth());
    }

    public synchronized double getCurrentHealth() {
        return clamp(currentHealth, 0.0, getMaxHealth());
    }

    /**
     * 現在 HP の基礎値を取得する（永続化用）。
     * <p>
     * 装備補正で最大 HP が増えている場合でも、基礎最大 HP を超える値は保存しない。
     *
     * @return 装備補正を含まない範囲に収めた現在 HP
     */
    public synchronized double getBaseCurrentHealth() {
        return clamp(currentHealth, 0.0, Math.max(MIN_MAX_HEALTH, maxHealth));
    }

    /**
     * 現在 HP を設定する（0〜最大 HP にクランプ）。
     *
     * @param currentHealth 現在 HP
     */
    public synchronized void setCurrentHealth(double currentHealth) {
        this.currentHealth = clamp(currentHealth, 0.0, getMaxHealth());
    }

    public synchronized double getMaxMana() {
        return Math.max(0.0, maxMana + bonuses.maxMana());
    }

    /**
     * 最大 MP の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない最大 MP
     */
    public synchronized double getBaseMaxMana() {
        return maxMana;
    }

    /**
     * 最大 MP を設定する（0 未満は 0）。現在 MP が上限を超える場合は上限へ揃える。
     *
     * @param maxMana 最大 MP
     */
    public synchronized void setMaxMana(double maxMana) {
        this.maxMana = Math.max(0.0, maxMana);
        this.currentMana = clamp(this.currentMana, 0.0, getMaxMana());
    }

    public synchronized double getCurrentMana() {
        return clamp(currentMana, 0.0, getMaxMana());
    }

    /**
     * 現在 MP の基礎値を取得する（永続化用）。
     * <p>
     * 装備補正で最大 MP が増えている場合でも、基礎最大 MP を超える値は保存しない。
     *
     * @return 装備補正を含まない範囲に収めた現在 MP
     */
    public synchronized double getBaseCurrentMana() {
        return clamp(currentMana, 0.0, Math.max(0.0, maxMana));
    }

    /**
     * 現在 MP を設定する（0〜最大 MP にクランプ）。
     *
     * @param currentMana 現在 MP
     */
    public synchronized void setCurrentMana(double currentMana) {
        this.currentMana = clamp(currentMana, 0.0, getMaxMana());
    }

    public synchronized double getAttackPower() {
        return attackPower + bonuses.attackPower();
    }

    /**
     * 攻撃力の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない攻撃力
     */
    public synchronized double getBaseAttackPower() {
        return attackPower;
    }

    public synchronized void setAttackPower(double attackPower) {
        this.attackPower = attackPower;
    }

    /**
     * 第 1 段階の防御力（バフ・スキル用）を取得する。
     * <p>
     * 防御貫通の影響を受けない基礎防御力。装備補正を加算して返す。
     *
     * @return 第 1 段階の防御力（装備補正込み）
     */
    public synchronized double getDefense1() {
        return Math.max(0.0, defense1 + bonuses.defense1());
    }

    /**
     * 第 1 段階の防御力の基礎値を取得する（永続化用）。
     */
    public synchronized double getBaseDefense1() {
        return defense1;
    }

    /**
     * 第 1 段階の防御力（バフ・スキル用）を設定する（0 未満は 0）。
     *
     * @param defense1 第 1 段階の防御力
     */
    public synchronized void setDefense1(double defense1) {
        this.defense1 = Math.max(0.0, defense1);
    }

    /**
     * 第 2 段階の防御力（防具用）を取得する。
     *
     * @return 第 2 段階の防御力（装備補正込み）
     */
    public synchronized double getDefense2() {
        return Math.max(0.0, defense2 + bonuses.defense2());
    }

    /**
     * 第 2 段階の防御力の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない防具防御力
     */
    public synchronized double getBaseDefense2() {
        return defense2;
    }

    /**
     * 第 2 段階の防御力（防具用）を設定する（0 未満は 0）。
     *
     * @param defense2 第 2 段階の防御力
     */
    public synchronized void setDefense2(double defense2) {
        this.defense2 = Math.max(0.0, defense2);
    }

    /**
     * 旧 API 互換。防具防御力（{@link #getDefense2()}）を返す。
     */
    public synchronized double getDefense() {
        return getDefense2();
    }

    /**
     * 旧 API 互換。防具防御力の基礎値を返す。
     */
    public synchronized double getBaseDefense() {
        return getBaseDefense2();
    }

    /**
     * 旧 API 互換。防具防御力（{@link #setDefense2(double)}）を設定する。
     */
    public synchronized void setDefense(double defense) {
        setDefense2(defense);
    }

    /**
     * 防御貫通率（0.0〜1.0）を取得する。
     * <p>
     * クラス基礎値と装備補正を合算して返す。スキル側の防御貫通は
     * {@link com.zeloria.combat.CombatService} で加算される。
     *
     * @return 防御貫通率
     */
    public synchronized double getArmorPenetration() {
        return clamp(armorPenetration + bonuses.armorPenetration(), 0.0, 1.0);
    }

    /**
     * 防御貫通率の基礎値を取得する（永続化・表示用）。
     *
     * @return 装備補正を含まない防御貫通率
     */
    public synchronized double getBaseArmorPenetration() {
        return armorPenetration;
    }

    /**
     * 防御貫通率の基礎値を設定する（0.0〜1.0 にクランプ）。
     *
     * @param armorPenetration 防御貫通率
     */
    public synchronized void setArmorPenetration(double armorPenetration) {
        this.armorPenetration = clamp(armorPenetration, 0.0, 1.0);
    }

    /**
     * 吸血率（0.0〜1.0）を取得する。
     * <p>
     * クラス基礎値と装備補正を合算して返す。スキル側の吸血率は
     * {@link com.zeloria.combat.CombatService} で加算される。
     *
     * @return 吸血率
     */
    public synchronized double getLifesteal() {
        return clamp(lifesteal + bonuses.lifesteal(), 0.0, 1.0);
    }

    /**
     * 吸血率の基礎値を取得する（永続化・表示用）。
     *
     * @return 装備補正を含まない吸血率
     */
    public synchronized double getBaseLifesteal() {
        return lifesteal;
    }

    /**
     * 吸血率の基礎値を設定する（0.0〜1.0 にクランプ）。
     *
     * @param lifesteal 吸血率
     */
    public synchronized void setLifesteal(double lifesteal) {
        this.lifesteal = clamp(lifesteal, 0.0, 1.0);
    }


    /* ------------------------------------------------------------------
     * クリティカル
     * ------------------------------------------------------------------ */

    public synchronized double getCriticalRate() {
        return clamp(criticalRate + bonuses.criticalRate(), MIN_PERCENT, MAX_PERCENT);
    }

    /**
     * クリティカル率の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まないクリティカル率
     */
    public synchronized double getBaseCriticalRate() {
        return criticalRate;
    }

    /**
     * クリティカル率（%）を設定する（0〜100 にクランプ）。
     *
     * @param criticalRate クリティカル率
     */
    public synchronized void setCriticalRate(double criticalRate) {
        this.criticalRate = clamp(criticalRate, MIN_PERCENT, MAX_PERCENT);
    }

    public synchronized double getCriticalDamage() {
        return criticalDamage + bonuses.criticalDamage();
    }

    /**
     * クリティカルダメージ倍率の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まないクリティカルダメージ倍率
     */
    public synchronized double getBaseCriticalDamage() {
        return criticalDamage;
    }

    /**
     * クリティカルダメージ倍率を設定する（クランプなし）。
     *
     * @param criticalDamage クリティカルダメージ倍率
     */
    public synchronized void setCriticalDamage(double criticalDamage) {
        this.criticalDamage = criticalDamage;
    }

    /* ------------------------------------------------------------------
     * 命中・回避
     * ------------------------------------------------------------------ */

    public synchronized double getAccuracy() {
        return clamp(accuracy + bonuses.accuracy(), MIN_PERCENT, MAX_PERCENT);
    }

    /**
     * 命中率の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない命中率
     */
    public synchronized double getBaseAccuracy() {
        return accuracy;
    }

    /**
     * 命中率（%）を設定する（0〜100 にクランプ）。
     *
     * @param accuracy 命中率
     */
    public synchronized void setAccuracy(double accuracy) {
        this.accuracy = clamp(accuracy, MIN_PERCENT, MAX_PERCENT);
    }

    public synchronized double getEvasion() {
        return clamp(evasion + bonuses.evasion(), MIN_PERCENT, MAX_PERCENT);
    }

    /**
     * 回避率の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない回避率
     */
    public synchronized double getBaseEvasion() {
        return evasion;
    }

    /**
     * 回避率（%）を設定する（0〜100 にクランプ）。
     *
     * @param evasion 回避率
     */
    public synchronized void setEvasion(double evasion) {
        this.evasion = clamp(evasion, MIN_PERCENT, MAX_PERCENT);
    }

    public synchronized double getDodgeCooldownReduction() {
        return clamp(dodgeCooldownReduction + bonuses.dodgeCooldownReduction(),
                MIN_REDUCTION, MAX_REDUCTION);
    }

    /**
     * 回避クールダウン短縮率の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない短縮率
     */
    public synchronized double getBaseDodgeCooldownReduction() {
        return dodgeCooldownReduction;
    }

    /**
     * 回避クールダウン短縮率を設定する（0.0〜0.5 にクランプ）。
     *
     * @param dodgeCooldownReduction 短縮率
     */
    public synchronized void setDodgeCooldownReduction(double dodgeCooldownReduction) {
        this.dodgeCooldownReduction =
                clamp(dodgeCooldownReduction, MIN_REDUCTION, MAX_REDUCTION);
    }

    /* ------------------------------------------------------------------
     * クールダウン
     * ------------------------------------------------------------------ */

    public synchronized double getCooldownReduction() {
        return clamp(cooldownReduction + bonuses.cooldownReduction(),
                MIN_REDUCTION, MAX_REDUCTION);
    }

    /**
     * スキルクールタイム短縮率の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない短縮率
     */
    public synchronized double getBaseCooldownReduction() {
        return cooldownReduction;
    }

    /**
     * スキルクールタイム短縮率を設定する（0.0〜0.5 にクランプ）。
     *
     * @param cooldownReduction 短縮率
     */
    public synchronized void setCooldownReduction(double cooldownReduction) {
        this.cooldownReduction = clamp(cooldownReduction, MIN_REDUCTION, MAX_REDUCTION);
    }


    /* ------------------------------------------------------------------
     * 移動・リジェネ
     * ------------------------------------------------------------------ */

    public synchronized double getMoveSpeed() {
        return Math.max(0.0, moveSpeed + bonuses.moveSpeed());
    }

    /**
     * 移動速度の基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない移動速度
     */
    public synchronized double getBaseMoveSpeed() {
        return moveSpeed;
    }

    /**
     * 移動速度（WalkSpeed）を設定する。
     * <p>
     * Bukkit の {@code setWalkSpeed} は -1.0〜1.0 の範囲しか受け付けないため、
     * 適用側（{@code PlayerStatsService}）でクランプする。ここでは値の保持のみ行う。
     *
     * @param moveSpeed 移動速度
     */
    public synchronized void setMoveSpeed(double moveSpeed) {
        this.moveSpeed = moveSpeed;
    }

    public synchronized double getHealthRegenFlat() {
        return healthRegenFlat + bonuses.healthRegenFlat();
    }

    /**
     * ヘルス固定リジェネの基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない固定回復量
     */
    public synchronized double getBaseHealthRegenFlat() {
        return healthRegenFlat;
    }

    /**
     * ヘルス固定リジェネを設定する（マイナス値も許可）。
     *
     * @param healthRegenFlat 1 秒あたりの固定回復量
     */
    public synchronized void setHealthRegenFlat(double healthRegenFlat) {
        this.healthRegenFlat = healthRegenFlat;
    }

    public synchronized double getHealthRegenPercent() {
        return healthRegenPercent + bonuses.healthRegenPercent();
    }

    /**
     * ヘルス割合リジェネの基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない割合（比率）
     */
    public synchronized double getBaseHealthRegenPercent() {
        return healthRegenPercent;
    }

    /**
     * ヘルス割合リジェネを設定する（マイナス値も許可）。
     * <p>
     * 値は比率として扱う（{@code 0.5} で固定リジェネ量の 50% 増し）。
     *
     * @param healthRegenPercent 割合（比率）
     */
    public synchronized void setHealthRegenPercent(double healthRegenPercent) {
        this.healthRegenPercent = healthRegenPercent;
    }

    public synchronized double getManaRegenFlat() {
        return manaRegenFlat + bonuses.manaRegenFlat();
    }

    /**
     * マナ固定リジェネの基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない固定回復量
     */
    public synchronized double getBaseManaRegenFlat() {
        return manaRegenFlat;
    }

    /**
     * マナ固定リジェネを設定する（マイナス値も許可）。
     *
     * @param manaRegenFlat 1 秒あたりの固定回復量
     */
    public synchronized void setManaRegenFlat(double manaRegenFlat) {
        this.manaRegenFlat = manaRegenFlat;
    }

    public synchronized double getManaRegenPercent() {
        return manaRegenPercent + bonuses.manaRegenPercent();
    }

    /**
     * マナ割合リジェネの基礎値を取得する（永続化用）。
     *
     * @return 装備補正を含まない割合（比率）
     */
    public synchronized double getBaseManaRegenPercent() {
        return manaRegenPercent;
    }

    /**
     * マナ割合リジェネを設定する（マイナス値も許可）。
     * <p>
     * 値は比率として扱う（{@code 0.5} で固定リジェネ量の 50% 増し）。
     *
     * @param manaRegenPercent 割合（比率）
     */
    public synchronized void setManaRegenPercent(double manaRegenPercent) {
        this.manaRegenPercent = manaRegenPercent;
    }

    /* ------------------------------------------------------------------
     * 装備補正値
     * ------------------------------------------------------------------ */

    /**
     * 装備などによる補正値。
     * <p>
     * 各値は {@link PlayerStats} の取得メソッドと同じ単位で保持する
     * （割合系は比率、会心率などは % ポイント）。
     *
     * @param maxHealth              最大 HP 補正
     * @param maxMana                最大 MP 補正
     * @param attackPower            攻撃力補正
     * @param defense1               第 1 段階の防御力補正
     * @param defense2               第 2 段階の防御力補正
     * @param criticalRate           会心率補正（%）
     * @param criticalDamage         会心ダメージ倍率補正
     * @param accuracy               命中率補正（%）
     * @param evasion                回避率補正（%）
     * @param dodgeCooldownReduction 回避 CT 短縮率（比率）
     * @param cooldownReduction      スキル CT 短縮率（比率）
     * @param moveSpeed              移動速度補正
     * @param healthRegenFlat        HP 固定リジェネ補正
     * @param healthRegenPercent     HP 割合リジェネ補正（比率）
     * @param manaRegenFlat          MP 固定リジェネ補正
     * @param manaRegenPercent       MP 割合リジェネ補正（比率）
     * @param armorPenetration       防御貫通率（比率）
     * @param lifesteal              吸血率（比率）
     */
    public record Bonuses(
            double maxHealth,
            double maxMana,
            double attackPower,
            double defense1,
            double defense2,
            double criticalRate,
            double criticalDamage,
            double accuracy,
            double evasion,
            double dodgeCooldownReduction,
            double cooldownReduction,
            double moveSpeed,
            double healthRegenFlat,
            double healthRegenPercent,
            double manaRegenFlat,
            double manaRegenPercent,
            double armorPenetration,
            double lifesteal
    ) {

        /** 補正なし。 */
        public static final Bonuses NONE = new Bonuses(
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

        /**
         * 2 つの補正値を加算する。
         *
         * @param other 加算する補正値
         * @return 加算後の補正値
         */
        public Bonuses plus(Bonuses other) {
            if (other == null || other == NONE) {
                return this;
            }
            return new Bonuses(
                    maxHealth + other.maxHealth,
                    maxMana + other.maxMana,
                    attackPower + other.attackPower,
                    defense1 + other.defense1,
                    defense2 + other.defense2,
                    criticalRate + other.criticalRate,
                    criticalDamage + other.criticalDamage,
                    accuracy + other.accuracy,
                    evasion + other.evasion,
                    dodgeCooldownReduction + other.dodgeCooldownReduction,
                    cooldownReduction + other.cooldownReduction,
                    moveSpeed + other.moveSpeed,
                    healthRegenFlat + other.healthRegenFlat,
                    healthRegenPercent + other.healthRegenPercent,
                    manaRegenFlat + other.manaRegenFlat,
                    manaRegenPercent + other.manaRegenPercent,
                    armorPenetration + other.armorPenetration,
                    lifesteal + other.lifesteal
            );
        }
    }

    /**
     * 値を指定範囲へ丸める。
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

