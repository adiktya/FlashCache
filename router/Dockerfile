FROM eclipse-temurin:17-jdk AS build
WORKDIR /w
COPY . .
RUN chmod +x gradlew && ./gradlew :router:fatJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /w/router/build/libs/*-all.jar /app/router.jar
EXPOSE 7653
ENTRYPOINT ["java","-jar","/app/router.jar"]
