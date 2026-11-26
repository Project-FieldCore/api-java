# syntax=docker/dockerfile:1
# FieldOps API — image executável (PBI-0070). Sem segredos: JWT_SECRET, DATABASE_* etc. entram em runtime.

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Camada de dependências (invalidada só quando pom.xml / wrapper mudam).
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Testes rodam no CI (./mvnw -B verify); a imagem só empacota.
RUN ./mvnw -B -q clean package -DskipTests \
    && cp target/fieldcore-backend-*.jar /workspace/fieldops-api.jar

# ---- runtime ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S fieldops && adduser -S -G fieldops -H -s /sbin/nologin fieldops
WORKDIR /app
COPY --from=build --chown=fieldops:fieldops /workspace/fieldops-api.jar /app/fieldops-api.jar

USER fieldops
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    SERVER_PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/fieldops-api.jar"]
