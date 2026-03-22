FROM eclipse-temurin:25-jre-ubi10-minimal

WORKDIR /app

COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-Xms2g", "-Xmx2g", "-jar", "/app/app.jar"]
