FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends docker.io \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY app.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]