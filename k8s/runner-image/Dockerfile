# Multi-stage build for the Puklic self-hosted runner.
# Stage 1 pulls the actions-runner image solely to extract the runner binary +
# launcher scripts. Stage 2 builds the real image on a slim Ubuntu base so the
# 200+ MB of docker-in-docker (dockerd, containerd, runc, ctr, docker-proxy)
# that the actions-runner ships does NOT end up in the final layers — removing
# them in a child RUN only creates whiteouts and does not shrink the image.
#
# Budget: <1.5 GiB per issue #10.
# Design: docs/03_infrastructure/architect-reports/2026-05-24-k8s-runner-design.md §3.

# ── Stage 1: harvest the runner agent ────────────────────────────────────────
FROM ghcr.io/actions/actions-runner:2.323.0 AS runner-src
# Keep only what arc / actions/checkout actually invoke at run time. Drop
# externals/node{16,12,…} other than node20 — modern actions all use node20.
USER root
RUN set -eux; \
    find /home/runner/externals -mindepth 1 -maxdepth 1 \
        ! -name 'node20' -exec rm -rf {} +

# ── Stage 2: minimal runtime ─────────────────────────────────────────────────
FROM ubuntu:22.04

ARG ADOPTIUM_KEY_FP="3B04D753C9050D9A5D343F39843C48A565F8F04B"
ARG APPIMAGETOOL_URL="https://github.com/AppImage/AppImageKit/releases/download/13/appimagetool-x86_64.AppImage"
ARG APPIMAGETOOL_SHA="0019dfc4b32d63c1392aa264aed2253c1e0c2fb09216f8e2cc269bbfb8bb49b5"

ENV DEBIAN_FRONTEND=noninteractive

RUN set -eux; \
    # Bootstrap: tools needed only to fetch + verify Adoptium repo key.
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl wget gnupg sudo; \
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
    # Purge bootstrap-only packages; keep git (used by actions/checkout fetch-depth>1)
    # and sudo (runner needs to drop privileges).
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
        -exec rm -rf {} +; \
    # Strip JDK extras the runner never uses (saves ~100 MB).
    JDK_HOME=$(find /usr/lib/jvm -maxdepth 1 -type d -name 'temurin-21-jdk*' | head -1); \
    rm -f  "${JDK_HOME}/src.zip" "${JDK_HOME}/lib/src.zip"; \
    rm -rf "${JDK_HOME}/demo" \
           "${JDK_HOME}/sample" \
           "${JDK_HOME}/man"; \
    # X11 / icons / themes the runner does not render (saves ~30 MB).
    rm -rf \
        /usr/share/X11 \
        /usr/share/icons \
        /usr/share/themes \
        /usr/share/backgrounds \
        /usr/share/applications \
        /usr/share/mime; \
    # Keep only UTC + Europe/Prague tzdata (only if tzdata is installed — the
    # ubuntu:22.04 minimal base does not ship it; Compose Desktop builds don't
    # need it but the runner sets TZ for some plugins).
    if [ -d /usr/share/zoneinfo ]; then \
        find /usr/share/zoneinfo -mindepth 1 -maxdepth 1 \
            ! -name 'UTC' ! -name 'Etc' ! -name 'Europe' \
            ! -name 'posix' ! -name 'right' ! -name 'zone.tab' \
            ! -name 'zone1970.tab' ! -name 'iso3166.tab' ! -name 'leapseconds' \
            ! -name 'tzdata.zi' \
            -exec rm -rf {} +; \
        find /usr/share/zoneinfo/Europe -mindepth 1 -maxdepth 1 \
            ! -name 'Prague' -exec rm -rf {} +; \
    fi; \
    # GCC link-time-optimisation dump tool — debug-only (saves ~24 MB).
    rm -f /usr/bin/x86_64-linux-gnu-lto-dump-11 \
          /usr/bin/lto-dump-11

# Create the runner user (uid/gid 1001 to match the upstream actions-runner image).
RUN set -eux; \
    groupadd --gid 1001 runner; \
    useradd --uid 1001 --gid 1001 --shell /bin/bash --create-home runner; \
    # Passwordless sudo so the runner can install per-job deps if needed.
    echo "runner ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/runner; \
    chmod 0440 /etc/sudoers.d/runner

# Copy the runner agent + node20 only.
COPY --from=runner-src --chown=runner:runner /home/runner/ /home/runner/

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

USER runner
WORKDIR /home/runner
