import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
tasks.test { systemProperty("java.awt.headless", "true") }
tasks.withType<JavaExec>().configureEach {
    if (name == "run") System.getenv("ALTER_DATA_DIR")?.let { systemProperty("alter.dataDir", it) }
}
compose.desktop {
    application {
        mainClass = "alter.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Alter"
            packageVersion = "0.1.0"
            modules("java.sql", "java.net.http", "jdk.unsupported", "jdk.management")
            windows { menuGroup = "Alter"; shortcut = true }
        }
    }
}
