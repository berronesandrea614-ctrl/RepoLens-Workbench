# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Copy pom.xml first for layer caching
COPY pom.xml .
# Download dependencies (cached unless pom changes)
RUN mvn dependency:go-offline -B -q
# Copy source and build, skipping tests (CI already ran them)
COPY src ./src
RUN mvn package -B -DskipTests -q

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre AS runtime
LABEL maintainer="RepoLens"
WORKDIR /app

# Create non-root user
RUN groupadd -r repolens && useradd -r -g repolens repolens

# Copy built artifact
COPY --from=build /build/target/repolens-0.0.1-SNAPSHOT.jar app.jar

# Create workspace directory with proper permissions
RUN mkdir -p /app/workspace/repos && chown -R repolens:repolens /app

USER repolens

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
