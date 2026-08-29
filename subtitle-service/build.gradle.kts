import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.23.0"
    id("maven-publish")
    application
}

val versions = mapOf(
    "jacksonDatabindVersion" to "0.2.6",
    "swaggerAnnotationJakarta" to "2.2.52",
    "feignMicrometerVersion" to "13.14",
    "kuromojiVersion" to "0.9.0",
    "postgresDriverVersion" to "42.7.13",
    "logstashEncoderVersion" to "8.0",
)

group = "com.sublio"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

/*
──────────────────────────────────────────────────────
============== Resolve NEXUS credentials ==============
──────────────────────────────────────────────────────
*/

file(".env").takeIf { it.exists() }?.readLines()?.forEach {
    val (k, v) = it.split("=", limit = 2)
    System.setProperty(k.trim(), v.trim())
    logger.lifecycle("${k.trim()}=${v.trim()}")
}

val nexusUrl = System.getenv("NEXUS_URL") ?: System.getProperty("NEXUS_URL")
val nexusUser = System.getenv("NEXUS_USERNAME") ?: System.getProperty("NEXUS_USERNAME")
val nexusPassword = System.getenv("NEXUS_PASSWORD") ?: System.getProperty("NEXUS_PASSWORD")
val nexusConfigured = !nexusUrl.isNullOrBlank() && !nexusUser.isNullOrBlank() && !nexusPassword.isNullOrBlank()

if (!nexusConfigured) {
    logger.lifecycle(
        "NEXUS details are not set (NEXUS_URL, NEXUS_USERNAME, NEXUS_PASSWORD) — " +
                "falling back to mavenCentral, Nexus pull-through cache disabled for this build."
    )
}

fun RepositoryHandler.nexusRepo() {
    if (!nexusConfigured) return
    maven {
        url = uri(nexusUrl!!)
        isAllowInsecureProtocol = true
        credentials {
            username = nexusUser
            password = nexusPassword
        }
    }
}

repositories {
    gradlePluginPortal()
    maven("https://packages.confluent.io/maven/")
    nexusRepo()
    mavenCentral()
}

application {
    mainClass.set("subtitleservice.SubtitleServiceApplicationKt")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.0")
        mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.30.0")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("io.github.openfeign:feign-micrometer:${versions["feignMicrometerVersion"]}")
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.openapitools:jackson-databind-nullable:${versions["jacksonDatabindVersion"]}")
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:${versions["swaggerAnnotationJakarta"]}")

    implementation("org.postgresql:postgresql:${versions["postgresDriverVersion"]}")

    implementation("com.atilika.kuromoji:kuromoji-ipadic:${versions["kuromojiVersion"]}")
    implementation("net.logstash.logback:logstash-logback-encoder:${versions["logstashEncoderVersion"]}")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    runtimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

//──────────────────────────────────────────────────────
//============== Nexus Publishing ==============
//──────────────────────────────────────────────────────

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        nexusRepo()
    }
}

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set(rootDir.resolve("../api/subtitle-service.yaml").canonicalPath)
    outputDir.set(layout.buildDirectory.dir("generated").get().asFile.path)
    apiPackage.set("com.sublio.subtitleservice.api")
    invokerPackage.set("com.sublio.subtitleservice.invoker")
    modelPackage.set("com.sublio.subtitleservice.model")
    configOptions.set(mapOf(
        "dateLibrary" to "java8",
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "true"
    ))
}

// Generate Feign Client
tasks.register<GenerateTask>("openApiGenerateClient") {
    generatorName.set("kotlin-spring")
    inputSpec.set(rootDir.resolve("../api/subtitle-service.yaml").canonicalPath)
    outputDir.set(layout.buildDirectory.dir("generated-client").get().asFile.path)
    apiPackage.set("com.sublio.subtitleservice.client.api")
    invokerPackage.set("com.sublio.subtitleservice.client.invoker")
    modelPackage.set("com.sublio.subtitleservice.client.model")
    configOptions.set(mapOf(
        "dateLibrary" to "java8",
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "library" to "spring-cloud"   // <-- ключевая опция
    ))
}

sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/src/main/kotlin"))
            srcDir(layout.buildDirectory.dir("generated-client/src/main/kotlin"))
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
    dependsOn(tasks.named("openApiGenerateClient"))
}