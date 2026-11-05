plugins {
    id("java-library")
    id("com.gradleup.shadow") version "8.3.6"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // HikariCP を同梱するため implementation として宣言し、shadowJar で取り込む。
    implementation("com.zaxxer:HikariCP:6.2.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    // 依存ライブラリを含まない通常の jar は出力しない。
    // shadowJar と同一ファイル名 (classifier "") になるため、両方を有効にすると
    // 後から実行されたタスクが Fat JAR を上書きしてしまう。
    jar {
        enabled = false
    }

    shadowJar {
        // classifier ("-all") を除去し、Zeloria-0.1.jar として出力する。
        // HikariCP などの implementation 依存はすべて同梱される。
        archiveClassifier.set("")
        mergeServiceFiles()
    }

    assemble {
        dependsOn(shadowJar)
    }

    build {
        dependsOn(shadowJar)
    }
}
