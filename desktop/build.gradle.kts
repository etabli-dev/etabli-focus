// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
}

group = "com.raban.etabli.focus"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Same storage split as Android: timer state in Preferences DataStore,
    // history in SQLite with the Room schema.
    implementation("androidx.datastore:datastore-preferences-core:1.1.7")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// jlink/jpackage must come from a full JDK; use the toolchain rather than
// whichever JVM happens to run Gradle.
val toolchainJavaHome = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    .map { it.metadata.installationPath.asFile.absolutePath }

compose.desktop {
    application {
        mainClass = "com.raban.etabli.focus.MainKt"
        javaHome = toolchainJavaHome.get()

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "EtabliFocus"
            packageVersion = "0.1.0"
            description = "Track focused work blocks by category"
            vendor = "R. Heller"
            copyright = "Copyright 2026 R. Heller"
            licenseFile.set(rootProject.file("../LICENSE"))
            // From ./gradlew suggestRuntimeModules (sqlite-jdbc needs java.sql).
            modules("java.instrument", "java.sql", "jdk.unsupported")

            linux {
                packageName = "etabli-focus"
                // jpackage writes "<vendor> <debMaintainer>" into the control file.
                debMaintainer = "58561665+r-heller@users.noreply.github.com"
                appCategory = "utils"
                menuGroup = "Office"
                shortcut = true
                iconFile.set(project.file("packaging/icon.png"))
            }
            windows {
                // Fixed so that later MSIs upgrade this installation in place.
                upgradeUuid = "0561d667-2fa2-4565-abf5-6bda9380ed87"
                menuGroup = "Etabli"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
                iconFile.set(project.file("packaging/icon.ico"))
            }
            macOS {
                bundleID = "com.raban.etabli.focus"
                dockName = "Établi Focus"
                // Bundle versions must start at 1 on macOS; Linux and Windows
                // carry the real 0.1.0 preview version.
                packageVersion = "1.0.0"
                iconFile.set(project.file("packaging/icon.icns"))
            }
        }
    }
}
