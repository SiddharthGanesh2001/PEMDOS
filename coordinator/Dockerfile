FROM eclipse-temurin:17-jdk AS build
WORKDIR /project
COPY . .
RUN chmod +x gradlew && ./gradlew :coordinator:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /project/coordinator/build/libs/*[^plain].jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
