FROM ghcr.io/actions/actions-runner:2.323.0

USER root

# Adoptium JDK 21 (full JDK — Compose Desktop jpackage requires non-headless),
# C++ toolchain for libdave, .deb packaging tools, appimagetool.
#
# Everything in one layer + aggressive cleanup so transient apt metadata,
# GPG bootstrap tools, docs, man pages and non-English locales don't bloat
# the image. Budget: <1.5 GiB (issue #10).

ARG ADOPTIUM_KEY_FP="3B04D753C9050D9A5D343F39843C48A565F8F04B"
ARG APPIMAGETOOL_URL="https://github.com/AppImage/AppImageKit/releases/download/13/appimagetool-x86_64.AppImage"
ARG APPIMAGETOOL_SHA="0019dfc4b32d63c1392aa264aed2253c1e0c2fb09216f8e2cc269bbfb8bb49b5"

ENV DEBIAN_FRONTEND=noninteractive

RUN set -eux; \
    # Bootstrap: tools needed only to fetch + verify Adoptium repo key.
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl wget gnupg; \
    # Pin + import Adoptium key by fingerprint.
    wget -qO /tmp/adoptium.asc https://packages.adoptium.net/artifactory/api/gpg/key/public; \
    gpg --import-options show-only --import /tmp/adoptium.asc 2>&1 \
        | grep -q "$ADOPTIUM_KEY_FP" \
        || { echo "Adoptium GPG fingerprint mismatch"; exit 1; }; \
    gpg --dearmor < /tmp/adoptium.asc > /etc/apt/trusted.gpg.d/adoptium.gpg; \
    rm /tmp/adoptium.asc; \
    echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
        > /etc/apt/sources.list.d/adoptium.list; \
    apt-get update; \
    # Install runtime toolchain. ninja-build dropped (cmake default = make).
    # build-essential covers gcc/g++/make/libc-dev/dpkg-dev/fakeroot.
    apt-get install -y --no-install-recommends \
        temurin-21-jdk \
        build-essential cmake pkg-config \
        libssl-dev \
        git \
        fakeroot; \
    # Pin appimagetool by sha256 (uses curl from bootstrap before we purge it).
    curl -sSL -o /usr/local/bin/appimagetool "${APPIMAGETOOL_URL}"; \
    echo "${APPIMAGETOOL_SHA}  /usr/local/bin/appimagetool" | sha256sum -c -; \
    chmod +x /usr/local/bin/appimagetool; \
    # Purge bootstrap-only packages; keep git (used by actions/checkout fetch-depth>1).
    apt-get purge -y --auto-remove wget gnupg; \
    # Aggressive cleanup.
    apt-get clean; \
    rm -rf \
        /var/lib/apt/lists/* \
        /var/cache/apt/archives/* \
        /tmp/* \
        /var/tmp/* \
        /usr/share/doc/* \
        /usr/share/man/* \
        /usr/share/info/* \
        /usr/share/lintian/* \
        /usr/share/groff/* \
        /var/log/*.log; \
    # Strip non-English locales (keep en_US to avoid breaking POSIX tools).
    find /usr/share/locale -mindepth 1 -maxdepth 1 -type d \
        ! -name 'en' ! -name 'en_US' ! -name 'en@*' \
        -exec rm -rf {} +

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

USER runner
WORKDIR /home/runner
