import java.util.zip.ZipFile

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
    maven("https://maven.squiddev.cc/") { content { includeGroup("cc.tweaked") } }
    maven("https://maven.neoforged.net/releases/") { content { includeGroupAndSubgroups("net.neoforged") } }
}

val graalpyVersion = property("graalpy_version") as String

dependencies {
    compileOnly("net.fabricmc:fabric-loader:$loaderVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    // Game-side jars, provided by the server at runtime. Plain jars only: their POMs would pull in Minecraft.
    val provided = listOf(
        "cc.tweaked:cc-tweaked-$minecraftVersion-fabric:$ccVersion",
        "net.neoforged:bus:8.0.5",
        "net.neoforged.fancymodloader:loader:12.0.13",
        "com.google.guava:guava:33.6.0-jre",
    )
    provided.forEach {
        compileOnly(it) { isTransitive = false }
        testImplementation(it) { isTransitive = false }
    }

    // Bundled into the AIO jar: the mod is self-contained and needs no other Python mod.
    implementation("org.graalvm.polyglot:polyglot:$graalpyVersion")
    implementation("org.graalvm.polyglot:python-community:$graalpyVersion")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    // Test dependencies
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
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

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion
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
    systemProperty("org.sqlite.tmpdir", "${System.getProperty("user.home")}/.ccpython-cache/tmp")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g", "-XX:+UseParallelGC")
    // Servers run without -ea. GraalPy 25.5's Bytecode DSL interpreter trips one of its own asserts while building the
    // stack trace of an interrupted script (Context.interrupt), so keep asserts for our code only.
    jvmArgs("-da:com.oracle...", "-da:org.graalvm...")
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

// Service files (Truffle languages, polyglot implementation ...) from all bundled jars, concatenated: a plain
// merge would keep only one jar's copy and GraalPy would not find its language.
val mergeServices by tasks.registering {
    val bundled = configurations.runtimeClasspath
    val outDir = layout.buildDirectory.dir("merged-services")
    inputs.files(bundled)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("META-INF/services")
        dir.deleteRecursively()
        dir.mkdirs()
        val merged = sortedMapOf<String, LinkedHashSet<String>>()
        bundled.get().files.filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                    .forEach { entry ->
                        val lines = zip.getInputStream(entry).bufferedReader().readLines()
                            .map { it.substringBefore('#').trim() }
                            .filter { it.isNotEmpty() }
                        merged.getOrPut(entry.name.removePrefix("META-INF/services/")) { linkedSetOf() }.addAll(lines)
                    }
            }
        }
        merged.forEach { (name, lines) -> dir.resolve(name).writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}

tasks.register<Jar>("packageAioJar") {
    group = "build"
    description = "Packages the self-contained All-In-One JAR with GraalPy, its stdlib and sqlite-jdbc"
    archiveClassifier.set("aio")
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Earlier sources win on duplicates: our classes and metadata, then the merged service files.
    from(sourceSets.main.get().output)
    from(mergeServices)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude(
            "META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC",
            "META-INF/services/**", "module-info.class", "META-INF/versions/*/module-info.class"
        )
    }

    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Multi-Release" to "true",
            "Fabric-Loom-Version" to "1.17.11"
        )
    }
}

// Optional Cyrillic terminal font for the clients, as a resource pack (a server can push it with resource-pack=).
// It is licensed separately (CCPL), see resourcepack/CREDITS.md.
tasks.register<Zip>("packageResourcePack") {
    group = "build"
    description = "Packages the Cyrillic (CP1251) terminal font resource pack"
    archiveFileName.set("cc-python-cyrillic-$version.zip")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from("resourcepack")
}
