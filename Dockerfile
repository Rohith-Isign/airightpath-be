FROM eclipse-temurin:21-jdk-jammy

# Language toolchains for the coding-assessment engine. CodeExecutionEngine
# spawns these as real processes on this machine, so a binary that is missing
# here answers 503 COMPILER_UNAVAILABLE to the candidate rather than a compile
# error - the submission is fine, the server is not.
#
# build-essential is what makes C/C++ work: gcc and g++ alone cannot link
# without the libc headers and crt objects it brings.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg \
      python3 \
      build-essential \
 && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

# Prove every toolchain resolves at build time. A broken image should fail here,
# in CI, rather than mid-exam on a candidate's submission.
RUN java -version && javac -version \
 && python3 --version && node --version \
 && gcc --version && g++ --version

WORKDIR /app
COPY target/rightpath.jar rightpath.jar

# One image serves every environment; the port comes from the active profile
# (dev/prod 8081, stage 8082, uat 8083) and the -p mapping at run time. EXPOSE
# is documentation only, so all three are listed rather than one per build.
EXPOSE 8081 8082 8083

ENTRYPOINT ["java","-jar","/app/rightpath.jar"]
