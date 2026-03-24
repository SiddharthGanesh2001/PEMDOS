FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
RUN mkdir -p /data/shards
EXPOSE 5001
ENTRYPOINT ["java", "-jar", "app.jar"]
