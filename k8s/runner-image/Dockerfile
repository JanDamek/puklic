FROM ghcr.io/actions/actions-runner:2.323.0

USER root

# 1. JDK 21 Temurin with GPG fingerprint verification
ARG ADOPTIUM_KEY_FP="3B04D753C9050D9A5D343F39843C48A565F8F04B"
RUN apt-get update && apt-get install -y --no-install-recommends \
      wget gnupg ca-certificates \
 && wget -qO /tmp/adoptium.asc https://packages.adoptium.net/artifactory/api/gpg/key/public \
 && gpg --import-options show-only --import /tmp/adoptium.asc 2>&1 \
      | grep -q "$ADOPTIUM_KEY_FP" || (echo "Adoptium GPG fingerprint mismatch" && exit 1) \
 && gpg --dearmor < /tmp/adoptium.asc > /etc/apt/trusted.gpg.d/adoptium.gpg \
 && rm /tmp/adoptium.asc \
 && echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
      > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update && apt-get install -y --no-install-recommends temurin-21-jdk \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# 2. C++ toolchain + OpenSSL 3 + .deb packaging
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential cmake ninja-build pkg-config \
      libssl-dev \
      git curl zip unzip tar \
      fakeroot binutils dpkg-dev \
 && rm -rf /var/lib/apt/lists/*

# 3. appimagetool — pinned by SHA-256
ARG APPIMAGETOOL_URL="https://github.com/AppImage/AppImageKit/releases/download/13/appimagetool-x86_64.AppImage"
ARG APPIMAGETOOL_SHA="0019dfc4b32d63c1392aa264aed2253c1e0c2fb09216f8e2cc269bbfb8bb49b5"
RUN curl -sSL -o /usr/local/bin/appimagetool "${APPIMAGETOOL_URL}" \
 && echo "${APPIMAGETOOL_SHA}  /usr/local/bin/appimagetool" | sha256sum -c - \
 && chmod +x /usr/local/bin/appimagetool

USER runner
WORKDIR /home/runner
