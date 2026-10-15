plugins {
    kotlin("multiplatform") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

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
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val macosArm64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.register<Copy>("installToDist") {
    dependsOn("linkReleaseExecutableMacosArm64")
    from(layout.buildDirectory.dir("bin/macosArm64/releaseExecutable/atlas.kexe"))
    into(layout.projectDirectory.dir("dist"))
    rename { "atlas" }
}

tasks.register<Copy>("installToLocalBin") {
    dependsOn("linkReleaseExecutableMacosArm64")
    from(layout.buildDirectory.dir("bin/macosArm64/releaseExecutable/atlas.kexe"))
    into(file(System.getProperty("user.home") + "/.local/bin"))
    rename { "atlas" }
    doLast {
        file(System.getProperty("user.home") + "/.local/bin/atlas").setExecutable(true)
    }
}
