package com.zeloria.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.zeloria.Zeloria;

/**
 * 初回起動時にサンプルYML群を生成する。
 * 既存ファイルは上書きしない。
 */
public final class SampleConfigGenerator {

    private SampleConfigGenerator() {}

    public static void generateAll(Zeloria plugin) {
        File dir = plugin.getDataFolder();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        writeIfAbsent(new File(dir, "config.yml"), CONFIG_YML);
        writeIfAbsent(new File(dir, "handgun.yml"), HANDGUN_YML);
        writeIfAbsent(new File(dir, "block_filters.yml"), BLOCK_FILTERS_YML);
        writeIfAbsent(new File(dir, "hit_effects.yml"), HIT_EFFECTS_YML);
        writeIfAbsent(new File(dir, "entity_filters.yml"), ENTITY_FILTERS_YML);
        writeIfAbsent(new File(dir, "death_effects.yml"), DEATH_EFFECTS_YML);
    }

    private static void writeIfAbsent(File file, String content) {
        if (file.exists()) {
            return;
        }
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("サンプルYMLの生成に失敗しました: " + file.getName(), e);
        }
    }

    private static final String CONFIG_YML = """
            combat:
              internal-max-hp: 100.0
            """;

    private static final String HANDGUN_YML = """
            id: handgun
            name: "ハンドガン"
            material: minecraft:crossbow
            damage: 25.0
            headshot_multiplier: 2.0
            magazine_size: 12
            lore:
              - "標準的な拳銃"
            custom_model_data: 1
            sounds:
              fire: minecraft:entity.firework_rocket.blast
              reload: minecraft:block.note_block.hat
            """;

    private static final String BLOCK_FILTERS_YML = """
            penetrable_blocks:
              - minecraft:glass_pane
              - minecraft:glass
              - minecraft:iron_bars
            """;

    private static final String HIT_EFFECTS_YML = """
            block_hit:
              particle: minecraft:block
              count: 10
              sound: minecraft:block.stone.hit
            entity_hit:
              particle: minecraft:crit
              count: 5
              sound: minecraft:entity.player.attack.crit
            """;

    private static final String ENTITY_FILTERS_YML = """
            hittable_entities:
              - minecraft:player
              - minecraft:zombie
            """;

    private static final String DEATH_EFFECTS_YML = """
            death:
              title: "死亡してしまった！"
              subtitle: "復活まで %seconds% 秒"
              title_fade_in_ticks: 10
              title_stay_ticks: 40
              title_fade_out_ticks: 10
              respawn_wait_seconds: 5
              invulnerable_seconds: 3
              sounds:
                death: minecraft:entity.player.death
                countdown: minecraft:block.note_block.pling
                respawn: minecraft:entity.player.levelup
            """;
}
