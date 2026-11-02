# syntax=docker/dockerfile:1
#
# katar: a Spring Boot jar on a JRE, as a non-root user.
#
#   docker run -p 8080:8080 -e KATAR_DB_URL=jdbc:postgresql://db:5432/katar \
#     -e KATAR_DB_USER=katar -e KATAR_DB_PASSWORD=... ghcr.io/nafiz001/katar

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
# The tests run in CI before this image is built; here they would only repeat.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/katar-*.jar extract --layers --launcher --destination /out

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 katar
WORKDIR /app
# Dependencies change least often, the app's own classes most: one layer each,
# so a code change ships a few hundred kilobytes, not the whole jar.
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER katar
EXPOSE 8080
# Heap as a share of the container's memory limit, and a fast-starting GC for a small box.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
