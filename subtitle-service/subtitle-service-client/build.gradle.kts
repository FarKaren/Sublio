import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Plugins without versions — versions are set in the root build.gradle.kts.
// The Spring Boot plugin is NOT applied here: this needs a plain library jar, not a bootJar.
// Dependency repositories are provided by the root (subprojects { repositories { ... } }).
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.openapi.generator")
    `java-library`
    `maven-publish`
}

group = "com.sublio"
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

fun Project.stringProperty(name: String): String = property(name) as String

val swaggerAnnotationJakarta = stringProperty("swaggerAnnotationJakarta")
val jacksonDatabindVersion = stringProperty("jacksonDatabindVersion")

dependencies {
    // Versions come from the Spring Boot and Spring Cloud BOMs
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.0.0"))

    // Only needed for compilation: the consumer (a Spring Boot service) already has these
    compileOnly("org.springframework.cloud:spring-cloud-starter-openfeign")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")

    // Used in the generated models — passed through to the consumer
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("io.swagger.core.v3:swagger-annotations-jakarta:$swaggerAnnotationJakarta")
    api("org.openapitools:jackson-databind-nullable:$jacksonDatabindVersion")
}

/*
──────────────────────────────────────────────────────
====== Generate the Feign client from the same spec ======
──────────────────────────────────────────────────────
*/

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set(rootDir.resolve("../api/subtitle-service.yaml").canonicalPath)
    outputDir.set(layout.buildDirectory.dir("generated").get().asFile.path)
    apiPackage.set("com.sublio.subtitleservice.client.api")
    invokerPackage.set("com.sublio.subtitleservice.client.invoker")
    modelPackage.set("com.sublio.subtitleservice.client.model")
    configOptions.set(mapOf(
        "dateLibrary" to "java8",
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "library" to "spring-cloud"
    ))
}

sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/src/main/kotlin"))
        }
    }
}

tasks.named("compileKotlin") { dependsOn(tasks.named("openApiGenerate")) }
tasks.named("sourcesJar") { dependsOn(tasks.named("openApiGenerate")) }

/*
──────────────────────────────────────────────────────
============== Publishing to Nexus ==============
──────────────────────────────────────────────────────
SNAPSHOT → maven-snapshots (overwritable), release → maven-releases (immutable).
Login/password: from the environment (Taskfile passes them from .env)
or from the System properties set by the root build.gradle.kts.
*/

publishing {
    publications {
        create<MavenPublication>("client") {
            artifactId = "subtitle-service-client"
            from(components["java"])
            // Resolve actual BOM dependency versions into the published pom
            versionMapping {
                allVariants { fromResolutionResult() }
            }
        }
    }
    repositories {
        maven {
            name = "nexus"
            val base = System.getenv("NEXUS_BASE_URL") ?: "http://localhost:18081"
            val repo = if (version.toString().endsWith("SNAPSHOT")) "maven-snapshots" else "maven-releases"
            url = uri("$base/repository/$repo/")
            isAllowInsecureProtocol = true
            credentials {
                username = System.getenv("NEXUS_USERNAME") ?: System.getProperty("NEXUS_USERNAME")
                password = System.getenv("NEXUS_PASSWORD") ?: System.getProperty("NEXUS_PASSWORD")
            }
        }
    }
}