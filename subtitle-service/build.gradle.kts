plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.23.0"
    application
}



group = "com.sublio"
version = "0.0.1-SNAPSHOT"

fun Project.stringProperty(name: String): String = property(name) as String

val testContainersVersion = stringProperty("testContainersVersion")
val jacksonDatabindVersion = stringProperty("jacksonDatabindVersion")
val swaggerAnnotationJakarta = stringProperty("swaggerAnnotationJakarta")
val feignMicrometerVersion = stringProperty("feignMicrometerVersion")
val kuromojiVersion = stringProperty("kuromojiVersion")
val postgresDriverVersion = stringProperty("postgresDriverVersion")
val logstashEncoderVersion = stringProperty("logstashEncoderVersion")
val vaultVersion = stringProperty("vaultVersion")


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

file(".env").takeIf { it.exists() }?.readLines()
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    ?.forEach {
        val (k, v) = it.split("=", limit = 2)
        System.setProperty(k.trim(), v.trim())
        logger.lifecycle("loaded ${k.trim()} from .env")   // values are not logged
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

subprojects {
    repositories {
        gradlePluginPortal()
        maven("https://packages.confluent.io/maven/")
        nexusRepo()
        mavenCentral()
    }
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
    implementation("org.springframework.cloud:spring-cloud-vault-config:$vaultVersion")

    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("io.github.openfeign:feign-micrometer:$feignMicrometerVersion")
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.openapitools:jackson-databind-nullable:$jacksonDatabindVersion")
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:$swaggerAnnotationJakarta")

    implementation("org.postgresql:postgresql:$postgresDriverVersion")

    implementation("com.atilika.kuromoji:kuromoji-ipadic:$kuromojiVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    // OBSERVABILITY
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-tracing")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql:${testContainersVersion}")
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


sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/src/main/kotlin"))
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
}