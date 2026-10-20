plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

group = "com.orion"
version = "0.7.0"

repositories {
    mavenCentral()
}

kotlin {
    macosArm64 {
        compilations.getByName("main") {
            cinterops {
                val sqlite3 by creating {
                    defFile(file("src/nativeInterop/cinterop/sqlite3.def"))
                }
            }
        }
        binaries {
            executable {
                entryPoint = "atlas.main"
                baseName = "atlas"
                linkerOpts("-lsqlite3")
            }
        }
    }

    sourceSets {
        val macosArm64Main by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        val macosArm64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.register("test") {
    dependsOn("macosArm64Test")
}

tasks.register<Exec>("atlasIndex") {
    group = "verification"
    description = "Indexes the code-atlas project using the compiled release binary."
    dependsOn("linkReleaseExecutableMacosArm64")
    val binary = layout.buildDirectory.file("bin/macosArm64/releaseExecutable/atlas.kexe")
    commandLine(binary.get().asFile.absolutePath, "index", "--project", "code-atlas")
}

tasks.register<Exec>("atlasAudit") {
    group = "verification"
    description = "Runs the Atlas architectural audit quality gate against the code-atlas project."
    dependsOn("atlasIndex")
    val binary = layout.buildDirectory.file("bin/macosArm64/releaseExecutable/atlas.kexe")
    commandLine(binary.get().asFile.absolutePath, "audit", "--project", "code-atlas")
}

tasks.register<Exec>("atlasComplexityAudit") {
    group = "verification"
    description = "Runs the Atlas complexity ratchet quality gate against the code-atlas project."
    dependsOn("atlasIndex")
    val binary = layout.buildDirectory.file("bin/macosArm64/releaseExecutable/atlas.kexe")
    commandLine(binary.get().asFile.absolutePath, "audit", "--complexity", "--project", "code-atlas")
}

tasks.named("check") {
    dependsOn("atlasAudit", "atlasComplexityAudit")
}

tasks.register<Copy>("installToDist") {
    dependsOn("linkReleaseExecutableMacosArm64")
    from(layout.buildDirectory.file("bin/macosArm64/releaseExecutable/atlas.kexe"))
    into(layout.projectDirectory.dir("dist"))
    rename { "atlas" }
    doFirst {
        layout.projectDirectory.file("dist/atlas").asFile.delete()
    }
    doLast {
        layout.projectDirectory.file("dist/atlas").asFile.setExecutable(true)
    }
}

tasks.register<Copy>("installToLocalBin") {
    dependsOn("linkReleaseExecutableMacosArm64")
    from(layout.buildDirectory.file("bin/macosArm64/releaseExecutable/atlas.kexe"))
    into(file(System.getProperty("user.home") + "/.local/bin"))
    rename { "atlas" }
    doFirst {
        file(System.getProperty("user.home") + "/.local/bin/atlas").delete()
    }
    doLast {
        file(System.getProperty("user.home") + "/.local/bin/atlas").setExecutable(true)
    }
}
