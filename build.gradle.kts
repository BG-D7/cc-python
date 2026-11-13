plugins {
    java
}

val modId = property("mod_id") as String
val modName = property("mod_name") as String
val modVersion = property("mod_version") as String
val modGroup = property("mod_group") as String
val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val ccVersion = property("cc_version") as String

group = modGroup
version = "$modVersion+$minecraftVersion"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://squiddev.cc/maven/")
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:$loaderVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    
    // Offline / pre-cached local dependencies from libs/
    compileOnly(fileTree("libs") { include("*.jar") })

    // Test dependencies
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(fileTree("libs") { include("*.jar") })
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    systemProperty("polyglotimpl.DisableMultiReleaseCheck", "true")
    systemProperty("org.sqlite.tmpdir", "${System.getProperty("user.home")}/.kik-graalpy-cache/tmp")
}

tasks.jar {
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Multi-Release" to "true",
            "Fabric-Loom-Version" to "1.17.11"
        )
    }
}

tasks.register<Jar>("packageAioJar") {
    group = "build"
    description = "Packages the All-In-One JAR embedding GraalPy runtime and stdlib"
    archiveClassifier.set("aio")

    from(sourceSets.main.get().output)
    
    val pyfishJar = file("libs/pyfish-fabric-26.2-1.2.7.jar")
    if (pyfishJar.exists()) {
        from(zipTree(pyfishJar)) {
            exclude("fabric.mod.json")
            exclude("META-INF/MANIFEST.MF")
        }
    }

    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Multi-Release" to "true",
            "Fabric-Loom-Version" to "1.17.11"
        )
    }
}
