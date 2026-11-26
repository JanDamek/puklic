#!/bin/sh
# Provides a virtual X display (and the objcopy that jlink needs) without root: downloads the Ubuntu packages for Xvfb and the
# X11/GL client libraries into $PERF_X_ROOT (default /tmp/puklic-perf-x), works around Xvfb's
# hard-coded /usr/bin/xkbcomp (binary-patched to the 8-byte path /tmp/xk, a symlink to our copy)
# and starts Xvfb. Prints `export` lines for DISPLAY, LD_LIBRARY_PATH and PATH; use with eval.
# Needs: apt-get (any user), dpkg, python3, network access to the Ubuntu archive.
set -eu

ROOT="${PERF_X_ROOT:-/tmp/puklic-perf-x}"
DISPLAY_NUM="${PERF_DISPLAY_NUM:-77}"
LIBS="$ROOT/root/usr/lib/x86_64-linux-gnu:$ROOT/root/lib/x86_64-linux-gnu"
APT="-o Dir::State=$ROOT/apt/state -o Dir::Cache=$ROOT/apt/cache"
PACKAGES="xvfb xauth x11-xkb-utils xkb-data libx11-6 libxext6 libxrender1 libxtst6 libxi6 libgl1 libglx-mesa0 \
libgl1-mesa-dri libegl1 libegl-mesa0 libgbm1 fonts-dejavu-core libxrandr2 libxcursor1 libxfixes3 libxcb1 binutils-x86-64-linux-gnu"

install_packages() {
    mkdir -p "$ROOT/apt/state/lists/partial" "$ROOT/apt/cache/archives/partial" "$ROOT/debs" "$ROOT/root" "$ROOT/xk"
    apt-get $APT update >/dev/null
    wanted=$(apt-get $APT install -s --no-install-recommends $PACKAGES | awk '/^Inst/{print $2}')
    (cd "$ROOT/debs" && apt-get $APT download $wanted >/dev/null)
    for deb in "$ROOT"/debs/*.deb; do dpkg -x "$deb" "$ROOT/root"; done
}

patch_xkbcomp_location() {
    mkdir -p "$ROOT/bin"
    printf '#!/bin/sh\nLD_LIBRARY_PATH=%s exec %s/root/usr/bin/x86_64-linux-gnu-objcopy "$@"\n' "$LIBS" "$ROOT" > "$ROOT/bin/objcopy"
    chmod +x "$ROOT/bin/objcopy"
    cp "$ROOT/root/usr/bin/xkbcomp" "$ROOT/xk/xkbcomp.real"
    printf '#!/bin/sh\nexport LD_LIBRARY_PATH=%s\nexec %s/xk/xkbcomp.real -I%s/root/usr/share/X11/xkb "$@"\n' \
        "$LIBS" "$ROOT" "$ROOT" > "$ROOT/xk/xkbcomp"
    chmod +x "$ROOT/xk/xkbcomp"
    ln -sfn "$ROOT/xk" /tmp/xk
    python3 -I - "$ROOT" <<'PY'
import sys
root = sys.argv[1]
data = open(f"{root}/root/usr/bin/Xvfb", "rb").read()
assert data.count(b"/usr/bin\0") == 1
open(f"{root}/Xvfb", "wb").write(data.replace(b"/usr/bin\0", b"/tmp/xk\0\0"))
PY
    chmod +x "$ROOT/Xvfb"
}

[ -x "$ROOT/Xvfb" ] || { install_packages; patch_xkbcomp_location; }

if [ ! -S "/tmp/.X11-unix/X$DISPLAY_NUM" ]; then
    mkdir -p /tmp/.X11-unix
    LD_LIBRARY_PATH="$LIBS" nohup "$ROOT/Xvfb" ":$DISPLAY_NUM" -screen 0 1280x800x24 \
        -xkbdir "$ROOT/root/usr/share/X11/xkb" -nolisten tcp >"$ROOT/xvfb.log" 2>&1 &
    sleep 3
fi
echo "export DISPLAY=:$DISPLAY_NUM"
echo "export LD_LIBRARY_PATH=$LIBS"
echo "export PATH=$ROOT/bin:\$PATH"
