FROM eclipse-temurin:17-jdk AS build
WORKDIR /w
COPY . .
RUN chmod +x gradlew && ./gradlew :server:fatJar --no-daemon

FROM eclipse-temurin:17-jre
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /w/server/build/libs/*-all.jar /app/flashcache.jar
EXPOSE 7654 7655
ENV FLASHCACHE_PORT=7654
ENV FLASHCACHE_METRICS_PORT=7655
HEALTHCHECK --interval=5s --timeout=2s --retries=30 \
  CMD curl -fsS http://127.0.0.1:7655/metrics >/dev/null || exit 1
ENTRYPOINT ["java","-jar","/app/flashcache.jar"]
