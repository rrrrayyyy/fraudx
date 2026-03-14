FROM eclipse-temurin:25-jre-ubi10-minimal

WORKDIR /app

COPY src/main/resources/rules.yaml rules.yaml
COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-Xms2g", "-Xmx2g", "-jar", "/app/app.jar"]
