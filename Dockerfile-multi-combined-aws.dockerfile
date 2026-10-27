# Multi-stage build for combining Java, Python, Node.js, and GCC

# Stage 1: Build Python (Debian slim)
FROM python:3.12.2-slim AS python-builder
RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
  && rm -rf /var/lib/apt/lists/*

# Stage 2: Build Node.js environment (Debian slim)
FROM node:22.11.0-slim AS node-builder
# Optionally preload global tools:
# RUN npm install -g typescript @types/node

# Stage 3: GCC builder (Debian)
FROM gcc:12.5.0 AS gcc-builder
# Any GCC-specific setup can go here

# Stage 4: Final runtime (Debian slim to match the builder stages)
FROM eclipse-temurin:21-jdk-jammy

# Install minimal system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl wget git ca-certificates \
  && rm -rf /var/lib/apt/lists/*

# Bring Python, Node, and GCC toolchains into the final image (optional; keep only if needed at runtime)
COPY --from=python-builder /usr/local /usr/local/python
ENV PATH="/usr/local/python/bin:${PATH}"
ENV PYTHONPATH="/usr/local/python/lib/python3.12/site-packages"

COPY --from=node-builder /usr/local /usr/local/node
ENV PATH="/usr/local/node/bin:${PATH}"

COPY --from=gcc-builder /usr/local /usr/local/gcc
ENV PATH="/usr/local/gcc/bin:${PATH}"
ENV LD_LIBRARY_PATH="/usr/local/gcc/lib64"

# Create non-root user
RUN groupadd -r -g 1001 appuser \
 && useradd -r -u 1001 -g 1001 -d /app -s /bin/bash appuser


# Copy application JAR

# Optional: quick tool verification
# RUN java -version && python3 --version && node --version && gcc --version

#USER appuser
WORKDIR /app
COPY target/rightpath.jar rightpath.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/rightpath.jar"]

