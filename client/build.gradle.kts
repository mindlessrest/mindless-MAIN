import org.apache.commons.lang3.SystemUtils

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.5"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val baseGroup: String by project
val mcVersion: String by project
val mindlessForgeVersion = providers.gradleProperty("mindlessForgeVersion")
    .orElse("1.8.9-11.15.1.2318-1.8.9")
    .get()
val version: String by project
val modid: String by project
val transformerFile = file("src/main/resources/accesstransformer.cfg")

// "forge" = normal mod (Mixin). "injectable" = native DLL injection (ClassTransform/JVMTI).
val mindlessBuildType: String = run {
    val explicit = project.findProperty("mindlessBuildType") as String?
    if (explicit != null) return@run explicit
    val requestedTasks = gradle.startParameter.taskNames
    if (requestedTasks.any { it.contains("Injection") || it.contains("lunarPayload") || it.contains("Native") })
        "injectable" else "forge"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

loom {
    log4jConfigs.from(file("log4j2.xml"))
    launchConfigs {
        "client" {
            if (mindlessBuildType == "forge") {
                property("mixin.debug", "true")
                arg("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
            }
        }
    }
    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                vmArgs.remove("-XstartOnFirstThread")
            }
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        if (mindlessBuildType == "forge") {
            mixinConfig("mixins.mindless.json")
        }
        if (transformerFile.exists()) {
            println("Installing access transformer")
            accessTransformer(transformerFile)
        }
    }

    if (mindlessBuildType == "forge") {
        mixin {
            defaultRefmapName.set("mixins.mindless.refmap.json")
        }
    }
}

val authSdkJavaDir = file("${System.getProperty("user.home")}/authsdk/java/src/main/java")

sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
    if (authSdkJavaDir.exists()) {
        java.srcDir(authSdkJavaDir)
        java.exclude("dev/authsys/Example.java")
    }
}

val lunarShim by sourceSets.creating {
    java.srcDir("src/lunarShim/java")
    compileClasspath += sourceSets.main.get().compileClasspath
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
    maven("https://repo.polyfrost.cc/releases/")
}

val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

configurations.named("testRuntimeClasspath") {
    exclude(group = "org.ow2.asm", module = "asm-debug-all")
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:$mindlessForgeVersion")

    compileOnly("net.lenni0451.classtransform:core:1.15.1")
    compileOnly("net.lenni0451.classtransform:additionalclassprovider:1.15.1")

    if (mindlessBuildType == "forge") {
        shadowImpl("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
            isTransitive = false
            exclude(module = "gson")
            exclude(module = "guava")
            exclude(module = "jarjar")
            exclude(module = "commons-codec")
            exclude(module = "commons-io")
            exclude(module = "launchwrapper")
            exclude(module = "asm-commons")
            exclude(module = "slf4j-api")
        }
        annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT")
    } else {
        shadowImpl("net.lenni0451.classtransform:core:1.15.1")
        shadowImpl("net.lenni0451.classtransform:additionalclassprovider:1.15.1")
    }
    shadowImpl("org.java-websocket:Java-WebSocket:1.6.0")
    shadowImpl("org.slf4j:slf4j-api:2.0.13")
    shadowImpl("net.java.dev.jna:jna:5.14.0")
    shadowImpl("com.squareup.okhttp3:okhttp:4.12.0")
    // Lunar may run on a JRE without javax.tools' system compiler. Keep script
    // compilation available inside the payload instead of requiring launcher Java configuration.
    shadowImpl("org.eclipse.jdt:ecj:3.24.0")

    compileOnly("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
        isTransitive = false
        exclude(module = "gson")
        exclude(module = "guava")
        exclude(module = "jarjar")
        exclude(module = "commons-codec")
        exclude(module = "commons-io")
        exclude(module = "launchwrapper")
        exclude(module = "asm-commons")
        exclude(module = "slf4j-api")
    }

    testImplementation("junit:junit:4.13.2")
    // The transformer compatibility tests are written against the weaving library and ASM, which
    // reach the main source set through compileOnly/shadow and so never landed on the test
    // classpath. Without these the whole test source set fails to compile, which is why the
    // transformer suite has been skipped rather than run.
    testImplementation("net.lenni0451.classtransform:core:1.15.1")
    testImplementation("net.lenni0451.classtransform:additionalclassprovider:1.15.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("mindless.testForgeVersion", mindlessForgeVersion)
    providers.systemProperty("mindless.lunarBake").orNull?.let {
        systemProperty("mindless.lunarBake", it)
    }
}

tasks.withType(org.gradle.jvm.tasks.Jar::class) {
    archiveBaseName.set(modid)
    manifest.attributes.run {
        this["FMLCorePluginContainsFMLMod"] = "true"
        this["ForceLoadAsMod"] = "true"
        if (mindlessBuildType == "forge") {
            this["TweakClass"] = "org.spongepowered.asm.launch.MixinTweaker"
            this["MixinConfigs"] = "mixins.mindless.json"
        }
        if (transformerFile.exists())
            this["FMLAT"] = "${modid}_at.cfg"
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    inputs.property("basePackage", baseGroup)

    filesMatching(listOf("mcmod.info", "mixins.mindless.json")) {
        expand(inputs.properties)
    }

    rename("accesstransformer.cfg", "META-INF/${modid}_at.cfg")
}


val remapJar by tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveClassifier.set("")
    from(tasks.shadowJar)
    input.set(tasks.shadowJar.get().archiveFile)
}

tasks.jar {
    archiveClassifier.set("without-deps")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}

tasks.shadowJar {
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("non-obfuscated-with-deps")
    configurations = listOf(shadowImpl)
    from(sourceSets.main.get().output)

    exclude(
        "dummyThing",
        "LICENSE.txt",
        "META-INF/MUMFREY.RSA",
        "META-INF/maven/**",
        "org/**/*.html",
        "LICENSE.md",
        "pack.mcmeta",
        "**/module-info.class",
        "*.so",
        "*.dylib",
        "*.jnilib",
        "ibxm/**",
        "com/jcraft/**",
        "org/lwjgl/**",
        "net/java/**",
        "com/sun/jna/**/*.dll",
        "com/sun/jna/**/*.so",
        "com/sun/jna/**/*.dylib",
        "META-INF/proguard/**",
        "META-INF/versions/**",
        "META-INF/com.android.tools/**",
        "fabric.mod.json"
    )

    if (mindlessBuildType != "forge") {
        relocate("org.objectweb.asm", "mindless.deps.org.objectweb.asm")
    }
    relocate("org.slf4j", "mindless.deps.org.slf4j")
}

val forgeMappedJar = providers.provider {
    configurations.runtimeClasspath.get().files.firstOrNull { it.name == "forge-mapped.jar" }
        ?: error("Loom's MCP-mapped Forge JAR was not found on runtimeClasspath")
}

val lunarPayloadJar by tasks.registering(org.gradle.jvm.tasks.Jar::class) {
    group = "build"
    description = "Builds the self-contained MCP payload for Lunar 1.8.9 + OptiFine."
    dependsOn(tasks.shadowJar, tasks.named(lunarShim.classesTaskName))
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("lunar-mcp-with-forge")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(lunarShim.output)
    from({ zipTree(tasks.shadowJar.get().archiveFile.get().asFile) }) {
        exclude("META-INF/MANIFEST.MF")
    }
    from({ zipTree(forgeMappedJar.get()) }) {
        include("net/minecraftforge/**")
        include("mcpmod.info")
        exclude("net/minecraftforge/fml/common/eventhandler/EventBus.class")
    }
}

tasks.assemble.get().dependsOn(tasks.remapJar)

// ---------------------------------------------------------------------------
// MSA (Mindless Scripting API) — stub jar for offline script compilation.
// Contains all net.minecraft.*, net.minecraftforge.*, and mindless.script.*
// classes so scripts can compile without the game running.
// ---------------------------------------------------------------------------

val msaJar by tasks.registering(org.gradle.jvm.tasks.Jar::class) {
    group = "build"
    description = "Builds msa.jar (Mindless Scripting API) for offline script compilation."
    dependsOn(tasks.named("compileJava"))
    archiveBaseName.set("msa")
    archiveVersion.set("")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Our scripting API classes
    from(sourceSets.main.get().output) {
        include("mindless/script/**")
        include("mindless/module/Module.class")
        include("mindless/module/Module\$*.class")
        include("mindless/module/ModuleManager.class")
        include("mindless/module/setting/**")
        include("mindless/utility/**")
        include("mindless/event/**")
        include("mindless/Mindless.class")
    }

    // Minecraft + Forge classes from loom's mapped jar
    from({ zipTree(forgeMappedJar.get()) }) {
        include("net/minecraft/**")
        include("net/minecraftforge/**")
    }

    // Minecraft mapped classes from the actual MC jar on compileClasspath
    from({
        val mcJar = configurations.named("minecraftNamed").get().files.firstOrNull {
            it.name == "minecraft-mapped.jar" || it.name.contains("minecraft-mapped")
        }
        if (mcJar != null) zipTree(mcJar) else files()
    }) {
        include("net/minecraft/**")
    }
}

tasks.assemble.get().dependsOn(msaJar)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.isIncremental = true
    options.isFork = true
    options.forkOptions.jvmArgs = listOf("-Xmx2g", "-XX:+UseParallelGC")
    options.compilerArgs.addAll(listOf(
        "-nowarn",
        "-Xlint:none",
        "-Aquiet=true"
    ))
}

// ---------------------------------------------------------------------------
// Native injection bundle (MindlessNative.dll + MindlessInjector.exe).
// Requires: Visual Studio 2022 C++ x64, CMake >= 3.21, and a JDK exposing
// jni.h + jvmti.h (JDK 8 is fine). Set -PnativeJavaHome to point at it.
// ---------------------------------------------------------------------------

val nativeDir = layout.projectDirectory.dir("native").asFile
val nativeBuildDir = layout.buildDirectory.dir("native").get().asFile
val nativeDistDir = File(nativeBuildDir, "dist")
val injectionBundleDir = layout.buildDirectory.dir("injection").get().asFile

val nativeJavaHome: String? =
    (project.findProperty("nativeJavaHome") as String?)
        ?: System.getenv("JAVA_HOME")

fun payloadJarFile(): File =
    tasks.remapJar.get().archiveFile.get().asFile

fun lunarPayloadJarFile(): File =
    lunarPayloadJar.get().archiveFile.get().asFile

val configureNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Runs CMake to configure the MindlessNative build tree."
    dependsOn(tasks.remapJar, lunarPayloadJar)
    doFirst {
        val payload = payloadJarFile()
        val lunarPayload = lunarPayloadJarFile()
        require(payload.isFile) { "Payload JAR not produced: $payload" }
        require(lunarPayload.isFile) { "Lunar payload JAR not produced: $lunarPayload" }
        require(!nativeJavaHome.isNullOrBlank()) {
            "nativeJavaHome or JAVA_HOME must point at a JDK with jni.h/jvmti.h."
        }
        nativeBuildDir.mkdirs()
        commandLine(
            "cmake",
            "-S", nativeDir.absolutePath,
            "-B", nativeBuildDir.absolutePath,
            "-A", "x64",
            "-DMINDLESS_JAVA_HOME=$nativeJavaHome",
            "-DMINDLESS_FORGE_PAYLOAD_JAR=${payload.absolutePath}",
            "-DMINDLESS_LUNAR_PAYLOAD_JAR=${lunarPayload.absolutePath}"
        )
    }
}

val buildNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds MindlessNative.dll + MindlessInjector.exe in Release."
    dependsOn(configureNative)
    doFirst {
        commandLine(
            "cmake",
            "--build", nativeBuildDir.absolutePath,
            "--config", "Release",
            "--parallel"
        )
    }
}

val prepareInjectionBundle by tasks.registering(Sync::class) {
    group = "native"
    description = "Assembles build/injection/ with DLL, EXE, and README."
    dependsOn(buildNative)
    from(nativeDistDir) {
        include("MindlessNative.dll", "MindlessInjector.exe")
    }
    from(nativeDir) {
        include("README.md")
    }
    into(injectionBundleDir)
}

tasks.register("buildMinecraft") {
    group = "build"
    description = "Builds the mod and installs the fresh jar into the local Minecraft mods folder."
    dependsOn(remapJar)

    doFirst {
        logger.lifecycle("buildMinecraft: building Mindless bS and installing it into your .minecraft mods folder...")
    }

    doLast {
        val builtJar = remapJar.archiveFile.get().asFile
        val modsDir = file("C:/Users/stikr/AppData/Roaming/.minecraft/mods")

        if (!modsDir.exists()) {
            modsDir.mkdirs()
        }

        modsDir.listFiles { file ->
            file.isFile && file.name.startsWith("${modid}-") && file.name.endsWith(".jar")
        }?.forEach { existingJar ->
            if (!existingJar.delete()) {
                throw GradleException("Failed to delete existing mod jar: ${existingJar.absolutePath}")
            }
        }

        val installedJar = modsDir.resolve(builtJar.name)
        builtJar.copyTo(installedJar, overwrite = true)
        logger.lifecycle("buildMinecraft: installed ${installedJar.name} to ${modsDir.absolutePath}")
        logger.lifecycle("buildMinecraft: done")
    }
}

tasks.register("buildInjection") {
    group = "build"
    description = "Builds injectable JAR + Lunar payload + native DLL/EXE bundle."
    dependsOn(tasks.remapJar, lunarPayloadJar, prepareInjectionBundle)
    doFirst {
        logger.lifecycle("buildInjection: building injectable Mindless + native bundle...")
    }
    doLast {
        val builtJar = remapJar.archiveFile.get().asFile
        val lunarJar = lunarPayloadJar.get().archiveFile.get().asFile
        val injectionDir = injectionBundleDir

        copy {
            from(builtJar)
            into(injectionDir)
        }
        logger.lifecycle("buildInjection: ${builtJar.name} -> ${injectionDir.absolutePath}")
        logger.lifecycle("buildInjection: Lunar payload -> ${lunarJar.absolutePath}")
        logger.lifecycle("buildInjection: native bundle -> ${injectionDir.absolutePath}")
    }
}
