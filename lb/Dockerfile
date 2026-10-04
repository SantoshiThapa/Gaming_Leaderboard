# Build context = repository root (see docker-compose.yml / render.yaml)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY lb/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY lb/src ./src
COPY frontend ./src/main/resources/static
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/lb.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
