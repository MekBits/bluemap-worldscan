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

// One-time: copies only the tags the addon reads out of raw chunks of a private world into the
// committed test fixtures. Usage: ./gradlew reduceFixtures -PfixturesDir=<dir of raw .nbt chunks>
tasks.register<JavaExec>("reduceFixtures") {
    description = "Reduces raw chunk files in -PfixturesDir to the allow-listed tags in src/test/resources/chunks."
    group = "other"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dk.mekbits.bluemapworldscan.testsupport.FixtureReducer"
    val target = layout.projectDirectory.dir("src/test/resources/chunks").asFile.path
    doFirst {
        val source = providers.gradleProperty("fixturesDir").orNull
            ?: throw GradleException("pass the raw chunks directory with -PfixturesDir=<dir>")
        args(source, target)
    }
}

// Measures the live/markers.json size of a world backup without extracting it. Usage:
// ./gradlew measureBackup -Pbackup=<world backup .tar.gz> -Pdimension=<overworld|the_nether|the_end> -PmapId=<map id>
// (add -PstateOut=<file> to keep the scan state)
tasks.register<JavaExec>("measureBackup") {
    description = "Prints the size of the live/markers.json that the structure layers of a world backup give."
    group = "other"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dk.mekbits.bluemapworldscan.testsupport.MeasureFromBackup"
    maxHeapSize = "2g"
    doFirst {
        fun required(name: String) = providers.gradleProperty(name).orNull
            ?: throw GradleException("pass -P$name=<value>")
        args(listOfNotNull(
            required("backup"), required("dimension"), required("mapId"),
            providers.gradleProperty("stateOut").orNull))
    }
}

tasks.jar {
    archiveClassifier = "plain"
}

val libPackage = "dk.mekbits.bluemapworldscan.lib"

tasks.shadowJar {
    archiveClassifier = ""

    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }

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
