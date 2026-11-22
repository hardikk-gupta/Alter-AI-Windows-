import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.1"
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit"))
}
tasks.processResources { exclude("fonts/**") }
tasks.test { systemProperty("java.awt.headless", "true") }
tasks.withType<JavaExec>().configureEach {
    if (name == "run") System.getenv("ALTER_DATA_DIR")?.let { systemProperty("alter.dataDir", it) }
}
compose.desktop {
    application {
        mainClass = "alter.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            appResourcesRootDir.set(project.layout.buildDirectory.dir("package-resources"))
            packageName = "Alter"
            packageVersion = "0.2.3"
            modules("jdk.httpserver", "jdk.crypto.ec", "java.sql", "java.net.http", "jdk.unsupported", "jdk.management")
            windows {
                iconFile.set(project.file("packaging/alter.ico"))
                menuGroup = "Alter"
                menu = true
                shortcut = true
                perUserInstall = true
                upgradeUuid = "0d5d9a26-99a2-5541-bf39-a416d2b6947a"
            }
        }
    }
}

// Use the JDK installer template with Alter's workspace protection on both Windows formats.
if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.withType<AbstractJPackageTask>().matching { it.name in setOf("packageMsi", "packageExe") }.configureEach {
        dependsOn("createDistributable")
        val script = project.file("scripts/package_windows.ps1")
        val helper = layout.buildDirectory.file("windows-helper/Release/alter-upgrade.dll")
        inputs.files(script, helper)
        val image = layout.buildDirectory.dir("compose/binaries/main/app/Alter")
        inputs.dir(image)
        val format = name.removePrefix("package").lowercase()
        actions.clear()
        doLast {
            val args = mutableListOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath, "-JavaHome", javaHome.get(), "-Format", format,
                "-Version", packageVersion.get(), "-AppImage", image.get().asFile.absolutePath,
                "-OutputDir", destinationDir.get().asFile.absolutePath, "-UpgradeId", winUpgradeUuid.get())
            wixToolsetDir.orNull?.let { args.addAll(listOf("-WixDir", it.asFile.absolutePath)) }
            val result = providers.exec { commandLine(args); isIgnoreExitValue = true }
            print(result.standardOutput.asText.get())
            print(result.standardError.asText.get())
            result.result.get().assertNormalExitValue()
        }
    }
}
