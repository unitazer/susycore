plugins {
    alias(conventions.plugins.repositories)
    alias(conventions.plugins.minecraft)
    alias(conventions.plugins.spotless)
    alias(conventions.plugins.publish)
    alias(conventions.plugins.shadow)
    alias(conventions.plugins.jvmdg)
    alias(conventions.plugins.idea)
    eclipse
    alias(conventions.plugins.test)
    alias(conventions.plugins.jvm)
}

repositories {
    maven {
        name = "tterrag Maven"
        url = uri("https://maven.tterrag.com/")
    }
    maven {
        name = "GeckoLib"
        url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
    }
    maven {
        name = "ChickenBones"
        url = uri("https://chickenbones.net/maven/")
    }
}

dependencies {
    fun Provider<MinimalExternalModuleDependency>.deobf() = get().let {
        rfg.deobf("${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}")
    }

    // Mixinbooter 11.x breaks runtime (mixins with type-parameters, FMLDeobfuscatingRemapper)
    // So we use Mixinbooter 10.x here, which contains the mixin annotation processor.
    annotationProcessor(libs.mixinbooter)

    compileOnlyApi(deps.jspecify)
    compileOnlyApi(deps.annotations)

    implementation(deps.hei)
    implementation(deps.theOneProbe)

    // # Fix crashes on macOS with Narrator
    runtimeOnly(deps.osxNarratorBlocker)

    // # GregTech dependencies
    implementation(deps.codechickenlib) { isTransitive = false }
    implementation(deps.gregtech) { isTransitive = false }
    implementation(deps.gregicalityMultiblocks) { isTransitive = false }

    // # Transitive GregTech dependencies
    // CTM 1.0.2.31
    api(deps.craftTweaker2)
    api(deps.ae2Uel) { isTransitive = false }
    api(deps.ctm)

    // # GregTech Addons
    // Supercritical 0.2.5
    implementation(deps.supercritical.deobf())
    // GT-FO 1.12.4
    implementation(deps.gregtechFoodOption.deobf())

    // # GroovyScript
    api(deps.groovyScript) { isTransitive = false }

    // # Lib Mods we are using
    api(deps.modularUi) { isTransitive = false }
    api(deps.geckoLib.deobf())
    // SussyPatches 0.4.0
    api(deps.sussyPatches)

    // # Immersive Railroading dependencies
    // Universal Mod Care 1.2.1
    implementation(deps.universalModCore.deobf())
    // Track API 1.2.0
    implementation(deps.trackApi.deobf())
    // Immersive Railroading 1.10.0
    implementation(deps.immersiveRailroading.deobf())

    // # Recurrent Complex dependencies
    compileOnly(deps.recurrentComplex.deobf())
    compileOnly(deps.ivToolkit.deobf())

    compileOnly(deps.fluidloggedApi)

    // # Pyrotech dependencies
    compileOnly(deps.pyrotech.deobf())
    compileOnly(deps.athenaeum.deobf())
    compileOnly(deps.dropt.deobf())

    // # Other dependencies
    compileOnly(deps.biomesOPlenty.deobf())
    // XNet-1.8.3-ynet
    compileOnly(deps.ynetXnetFork.deobf())
    // ReFinedTools 7.77
    compileOnly(deps.refinedTools.deobf())
    compileOnly(deps.mcjtyLibRefilmed.deobf())
    compileOnly(deps.travelersBackpack.deobf())
    compileOnly(deps.bubblesABaublesFork.deobf())
    compileOnly(deps.barrelsDrumsStorageMore.deobf())
    // LittleTiles
    compileOnly(deps.creativeCore.deobf())
    compileOnly(deps.littleTiles.deobf())
    // OpenComputers
    implementation(deps.openComputers.deobf())

    // Multistorage
    compileOnly(deps.shetiphianCore.deobf())
    compileOnly(deps.multiStorage.deobf())

    // ProjectRed
    compileOnly(deps.chickenAsm) { isTransitive = false }
    compileOnly(deps.mrtjpCore.deobf())
    compileOnly(deps.cbMultipart.deobf())
    compileOnly(deps.projectRedCore.deobf())

    runtimeOnly(deps.serverUtil.deobf())

    //ICBM
    //temporary dep, will replace once we have our own missiles
    implementation(deps.icbm.deobf())

    // # Optional dependencies. Uncomment the ones you need
//    runtimeOnly(deps.theBeneath.deobf())
//    runtimeOnly(deps.realisticTerrainGenerationUnofficial.deobf())
//    runtimeOnly(deps.worldEdit.deobf())
//    runtimeOnly(deps.worldEditCuiForgeEdition3.deobf())
//    runtimeOnly(deps.configAnytime)
//    runtimeOnly(deps.flare.deobf())

    // # OptiFine
//    // Copied from GTCEu, originally used to download latest Vintagium from GitHub
//    // Using Gradle's Ant integration seems to be the least hacky way to download an arbitrary file without a plugin
//    file("libs/optifine").mkdirs()
//    ant.get(src = "https://github.com/OpenCubicChunks/OptiFineDevTweaker/releases/download/2.6.15/aa_do_not_rename_OptiFineDevTweaker-2.6.15-all.jar",
//            dest = "libs/optifine/",
//            skipexisting = "true")
//    // Download OptiFine from some random GitHub repo I found by just searching
//    // Since I failed to get the jar from https://optifine.net/home
//    ant.get(src = "https://github.com/SynArchive/OptiFine-Archive/raw/refs/heads/main/1.12.2/preview_OptiFine_1.12.2_HD_U_G6_pre1.jar",
//            dest = "libs/optifine/",
//            skipexisting = "true")
//    runtimeOnly(fileTree("libs/optifine") { include("*.jar") })
}

configurations {
    compileOnly {
        // exclude GNU trove, FastUtil is superior and still updated
        exclude(group = "net.sf.trove4j", module = "trove4j")
        // exclude javax.annotation from findbugs, JetBrains annotations are superior
        exclude(group = "com.google.code.findbugs", module = "jsr305")
        // exclude scala as we don't use it for anything and causes import confusion
        exclude(group = "org.scala-lang")
        exclude(group = "org.scala-lang.modules")
        exclude(group = "org.scala-lang.plugins")
    }
}

// Rust natives stuff
// ai generated because i dont care
data class RustTarget(
    val os: String,
    val arch: String,
    val triple: String,
    // cargo frontend
    val tool: String,
    val libExt: String,
    val libPrefix: String,
) {
    val id: String
        get() = "$os-$arch"
    val libName: String
        get() = "susycore_${arch}_${os}.$libExt"
    val releaseLibFile: File
        get() = File("src/main/rust/target/$triple/release/${libPrefix}susycore.$libExt")
}

val rustTargets = listOf(
    RustTarget("linux", "x86_64", "x86_64-unknown-linux-gnu", "cargo", "so", "lib"),
    RustTarget("linux", "aarch64", "aarch64-unknown-linux-gnu", "zigbuild", "so", "lib"),
    RustTarget("windows", "x86_64", "x86_64-pc-windows-msvc", "xwin", "dll", ""),
    RustTarget("windows", "aarch64", "aarch64-pc-windows-msvc", "xwin", "dll", ""),
    RustTarget("macos", "x86_64", "x86_64-apple-darwin", "zigbuild", "dylib", "lib"),
    RustTarget("macos", "aarch64", "aarch64-apple-darwin", "zigbuild", "dylib", "lib"),
)

val hostOs = when {
    System.getProperty("os.name").lowercase().contains("windows") -> "windows"
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    else -> "linux"
}
val hostArch = when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "aarch64"
    else -> "x86_64"
}
val hostTarget = rustTargets.first { it.os == hostOs && it.arch == hostArch }

fun stringSelection(prop: String, env: String): String? =
    (findProperty(prop) as String?) ?: providers.environmentVariable(env).orNull

fun String?.flag(): Boolean = this != null && this != "false" && this != "0"
fun String?.flagOr(default: Boolean): Boolean = if (this == null) default else flag()

val useDocker = stringSelection("rustDocker", "RUST_DOCKER").flagOr(default = true)
val rustTargetsToBuild: List<RustTarget> = when (val requested = stringSelection("rustTargets", "RUST_TARGETS")) {
    null -> listOf(hostTarget)
    else -> {
        val ids = requested.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        when {
            "all" in ids -> rustTargets
            else -> ids.map { id ->
                rustTargets.find { it.id == id || it == hostTarget && id == "host" }
                    ?: throw GradleException(
                        "Unknown rust target \"$id\". Available: ${rustTargets.map { it.id }}, host, all"
                    )
            }.distinct()
        }
    }
}
// targets that may actually be compiled on this machine
val rustBuildableTargets = rustTargetsToBuild.filter { it == hostTarget || useDocker }

val rustProjectDir = file("src/main/rust")
val rustSourcesDir = file("src/main/rust/susycore/src")
val nativesDir = file("src/main/resources/natives/susycore")
val rustBuildInputs = files(
    "src/main/rust/Cargo.toml",
    "src/main/rust/Cargo.lock",
    "src/main/rust/susycore/Cargo.toml",
    "src/main/rust/susycore/build.rs",
)

val rustDockerImage = "susycore-rust-builder"

val rustDevRelease = stringSelection("rustDevRelease", "RUST_DEV_RELEASE").flag()
val devLibFile = File("src/main/rust/target/${if (rustDevRelease) "release" else "debug"}/${hostTarget.libPrefix}susycore.${hostTarget.libExt}")

rustBuildableTargets.forEach { target ->
    tasks.register<Exec>("compileRust-${target.id}") {
        group = "rust"
        description = "Compiles the susycore Rust natives for ${target.triple}"
        workingDir = rustProjectDir
        if (target == hostTarget) {
            commandLine("cargo", "build", "--release", "--target", target.triple)
        } else {
            dependsOn("buildRustDockerImage", "prepareRustCargoVolume")
            commandLine(
                "docker", "run", "--rm",
                "-v", "${rustProjectDir.absolutePath}:/work",
                "-v", "susycore-rust-cargo:/usr/local/cargo",
                "-w", "/work",
                rustDockerImage,
                "cargo", target.tool,
                *(if (target.tool == "xwin") arrayOf("build") else emptyArray()),
                "--release", "--target", target.triple,
            )
        }
        inputs.dir(rustSourcesDir)
        inputs.files(rustBuildInputs)
        outputs.file(target.releaseLibFile)
    }
}

tasks.register<Exec>("buildRustDockerImage") {
    group = "rust"
    description = "Builds the Docker image used for cross-compiling the release natives"
    workingDir = rustProjectDir
    commandLine("docker", "build", "-q", "-t", rustDockerImage, ".")
}

tasks.register<Exec>("prepareRustCargoVolume") {
    group = "rust"
    description = "Populates the shared cargo cache volume from the Docker image (prevents a first-use race between parallel compile tasks)"
    dependsOn("buildRustDockerImage")
    outputs.upToDateWhen { false }
    commandLine(
        "docker", "run", "--rm",
        "-v", "susycore-rust-cargo:/usr/local/cargo",
        "--entrypoint", "true",
        rustDockerImage,
    )
}

tasks.register("buildRustNatives") {
    group = "build"
    description = "Compiles the susycore Rust natives for the selected targets (default: host platform, -PrustTargets=all for everything)"
    dependsOn(rustBuildableTargets.map { "compileRust-${it.id}" })
    finalizedBy("copyRustNatives")
}

tasks.register<Copy>("copyRustNatives") {
    group = "rust"
    description = "Copies built Rust natives into the resources to be packaged"
    dependsOn(rustBuildableTargets.map { "compileRust-${it.id}" })
    outputs.upToDateWhen { false }
    into(nativesDir)
    rustBuildableTargets.forEach { target ->
        from(target.releaseLibFile) {
            rename { target.libName }
        }
    }
}

tasks.register<Exec>("compileRustDev") {
    group = "rust"
    description = "Compiles the susycore Rust native for the dev machine (${if (rustDevRelease) "release" else "debug"} profile; -PrustDevRelease / RUST_DEV_RELEASE=1 switches to release)"
    workingDir = rustProjectDir
    commandLine("cargo", "build", *(if (rustDevRelease) arrayOf("--release") else emptyArray()))
    inputs.dir(rustSourcesDir)
    inputs.files(rustBuildInputs)
    outputs.file(devLibFile)
}

tasks.register<Copy>("copyRustNativesDev") {
    group = "rust"
    description = "Copies the dev Rust native into the resources for runClient"
    dependsOn("compileRustDev")
    outputs.upToDateWhen { false }
    into(nativesDir)
    val libName = hostTarget.libName
    from(devLibFile) {
        rename { libName }
    }
}

tasks.named("build") {
    dependsOn("buildRustNatives")
}

tasks.processResources {
    mustRunAfter("copyRustNatives", "copyRustNativesDev")
}

tasks.named("sourcesJar") {
    mustRunAfter("copyRustNatives")
}

tasks.named("runClient") {
    dependsOn("compileRustDev", "copyRustNativesDev")
}
