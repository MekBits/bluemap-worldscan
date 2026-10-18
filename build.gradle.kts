plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            ivy {
                name = "BlueMapAPI GitHub releases"
                url = uri("https://github.com/BlueMap-Minecraft/BlueMapAPI/releases/download")
                patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("de.bluecolored", "bluemap-api") }
    }
}

configurations.named("runtimeClasspath") {
    exclude(group = "org.jspecify", module = "jspecify")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

dependencies {
    compileOnly("de.bluecolored:bluemap-api:2.8.2")
    compileOnly("com.flowpowered:flow-math:1.0.3")
    compileOnly("org.jetbrains:annotations:26.1.0")

    implementation("net.kyori:adventure-nbt:5.2.0")
    implementation("at.yawk.lz4:lz4-java:1.10.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation("de.bluecolored:bluemap-api:2.8.2")
    testImplementation("com.flowpowered:flow-math:1.0.3")
    testCompileOnly("org.jetbrains:annotations:26.1.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("bluemap.addon.json") {
        expand("version" to project.version.toString())
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier = "plain"
}

val libPackage = "dk.mekbits.bluemapworldscan.lib"

tasks.shadowJar {
    archiveClassifier = ""

    relocate("com.google.gson", "$libPackage.gson")
    relocate("net.kyori", "$libPackage.kyori")
    relocate("net.jpountz", "$libPackage.jpountz")
    relocate("org.locationtech.jts", "$libPackage.jts")

    exclude("**/module-info.class")
    exclude("net/jpountz/util/*/*/liblz4-java.*")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
