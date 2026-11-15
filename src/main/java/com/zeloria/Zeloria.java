package com.zeloria;

import java.io.File;

import org.bukkit.plugin.java.JavaPlugin;

import com.zeloria.config.BlockFilterConfig;
import com.zeloria.config.DeathEffectConfig;
import com.zeloria.config.EntityFilterConfig;
import com.zeloria.config.HitEffectConfig;
import com.zeloria.config.SampleConfigGenerator;
import com.zeloria.config.ZeloriaConfig;
import com.zeloria.listener.PlayerJoinListener;
import com.zeloria.listener.SpectatorRespawnListener;
import com.zeloria.listener.WorldLoadListener;
import com.zeloria.state.FoodLevelService;
import com.zeloria.state.GameRuleConfigurator;
import com.zeloria.state.InternalHealthService;
import com.zeloria.weapon.WeaponRegistry;

/**
 * プラグインのエントリポイント。
 * 起動時に設定/武器/ステート/リスナーを初期化し、終了時にタスクを停止する。
 */
public final class Zeloria extends JavaPlugin {

    private ZeloriaConfig zeloriaConfig;
    private BlockFilterConfig blockFilterConfig;
    private HitEffectConfig hitEffectConfig;
    private EntityFilterConfig entityFilterConfig;
    private DeathEffectConfig deathEffectConfig;
    private WeaponRegistry weaponRegistry;
    private InternalHealthService internalHealthService;
    private FoodLevelService foodLevelService;
    private GameRuleConfigurator gameRuleConfigurator;

    @Override
    public void onEnable() {
        // 初回起動時にサンプルYMLを生成する（既存ファイルは上書きしない）。
        SampleConfigGenerator.generateAll(this);

        this.zeloriaConfig = new ZeloriaConfig(this);
        this.blockFilterConfig = new BlockFilterConfig(this);
        this.hitEffectConfig = new HitEffectConfig(this);
        this.entityFilterConfig = new EntityFilterConfig(this);
        this.deathEffectConfig = new DeathEffectConfig(this);

        this.weaponRegistry = new WeaponRegistry(this);
        this.weaponRegistry.loadWeapon(new File(getDataFolder(), "handgun.yml"));

        this.internalHealthService = new InternalHealthService(this, this.zeloriaConfig);
        this.foodLevelService = new FoodLevelService(this);
        this.gameRuleConfigurator = new GameRuleConfigurator(this);
        this.gameRuleConfigurator.applyAll();

        SpectatorRespawnListener spectatorRespawnListener =
                new SpectatorRespawnListener(this, this.internalHealthService, this.deathEffectConfig);
        this.internalHealthService.setDeathHandler(spectatorRespawnListener::handleDeath);

        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, this.internalHealthService, this.foodLevelService), this);
        getServer().getPluginManager().registerEvents(spectatorRespawnListener, this);
        getServer().getPluginManager().registerEvents(
                new WorldLoadListener(this, this.gameRuleConfigurator), this);

        this.foodLevelService.start();

        getLogger().info("Zeloria を有効化しました。");
    }

    @Override
    public void onDisable() {
        if (this.foodLevelService != null) {
            this.foodLevelService.stop();
        }
        getLogger().info("Zeloria を無効化しました。");
    }

    public ZeloriaConfig getZeloriaConfig() {
        return this.zeloriaConfig;
    }

    public BlockFilterConfig getBlockFilterConfig() {
        return this.blockFilterConfig;
    }

    public HitEffectConfig getHitEffectConfig() {
        return this.hitEffectConfig;
    }

    public EntityFilterConfig getEntityFilterConfig() {
        return this.entityFilterConfig;
    }

    public DeathEffectConfig getDeathEffectConfig() {
        return this.deathEffectConfig;
    }

    public WeaponRegistry getWeaponRegistry() {
        return this.weaponRegistry;
    }

    public InternalHealthService getInternalHealthService() {
        return this.internalHealthService;
    }

    public FoodLevelService getFoodLevelService() {
        return this.foodLevelService;
    }

    public GameRuleConfigurator getGameRuleConfigurator() {
        return this.gameRuleConfigurator;
    }
}
