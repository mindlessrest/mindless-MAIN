plugins {
    java
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

val legacyAsm by configurations.creating

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.ow2.asm:asm-analysis:9.7.1")
    legacyAsm("org.ow2.asm:asm:5.2")
    legacyAsm("org.ow2.asm:asm-tree:5.2")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("legacyAsmClasspath", legacyAsm.asPath)
}

application {
    mainClass.set("obf.Main")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "obf.Main"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
