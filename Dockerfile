# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build
# Uses a full Maven + JDK 21 image to compile the source and produce a fat JAR.
# The pom.xml is copied separately before the source so Docker can cache the
# dependency-download layer. Only a change to pom.xml triggers a re-download.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app


COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

COPY src ./src
RUN mvn clean package -DskipTests -B --no-transfer-progress

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
# Uses a minimal JRE-only image. Maven, source code, and build tools are not
# included, keeping the final image small and the attack surface low.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Install Tesseract OCR and English language data (~30 MB).
# Required by tess4j for OCR on image-only PDF pages.
# The data files land at /usr/share/tessdata/ which matches ocr.tesseract.datapath default.
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-eng

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
