FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY pom.xml .
COPY platform/pom.xml platform/pom.xml
COPY payment-simulator/pom.xml payment-simulator/pom.xml
COPY platform/src platform/src
COPY payment-simulator/src payment-simulator/src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
ARG MODULE=platform
WORKDIR /app
COPY --from=build /source/${MODULE}/target/${MODULE}-1.0.0.jar /app/app.jar
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
