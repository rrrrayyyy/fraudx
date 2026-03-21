FROM eclipse-temurin:25-jre-ubi10-minimal

WORKDIR /app

COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-Xms1g", "-Xmx1g", "-jar", "/app/app.jar"]
