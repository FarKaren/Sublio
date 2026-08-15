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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("subtitleservice.SubtitleServiceApplicationKt")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.openapitools:jackson-databind-nullable:0.2.6")
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.52")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
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