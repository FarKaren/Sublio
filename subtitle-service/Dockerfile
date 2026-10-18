FROM gradle:8.14.2-jdk24 AS build
ARG NEXUS_URL=http://host.docker.internal:18081/repository/maven-snapshots
ARG NEXUS_USERNAME=admin
ARG NEXUS_PASSWORD=admin

ENV NEXUS_URL=${NEXUS_URL}
ENV NEXUS_USERNAME=${NEXUS_USERNAME}
ENV NEXUS_PASSWORD=${NEXUS_PASSWORD}

WORKDIR /home/gradle/project

COPY api/subtitle-service.yaml /home/gradle/api/subtitle-service.yaml

COPY subtitle-service/gradle.properties .
COPY subtitle-service/build.gradle.kts .
COPY subtitle-service/settings.gradle.kts .
COPY subtitle-service/src ./src

RUN gradle --no-daemon clean build -x test

FROM eclipse-temurin:24-jre

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

RUN groupadd -r spring && useradd -r -g spring spring

WORKDIR /app

COPY --from=build /home/gradle/project/build/libs/*.jar app.jar

RUN chown spring:spring app.jar

USER spring

EXPOSE 8084
ENTRYPOINT ["/bin/sh", "-c", "export VAULT_ROLE_ID=$(cat /vault/config/role-id) && exec java -jar app.jar"]