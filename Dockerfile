# Anexo B del PRD. Los tests corren en CI (GitHub Actions), no aquí.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Capa de dependencias: solo se reconstruye si cambia el pom
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests, Spotless, Checkstyle y JaCoCo corren en CI; aquí solo se empaqueta
RUN ./mvnw -B -q package -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -Djacoco.skip=true

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 tunai
COPY --from=build /app/target/tunai-api-*.jar app.jar
USER tunai
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
