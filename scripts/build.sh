#!/usr/bin/env bash
#
# Automated build script for RigelMCMod. Thin wrapper around the Gradle build
# documented in README.md's "Building" section - use this when you just want a jar
# (and optionally have it dropped straight into a test server's plugins/ folder)
# without having to remember the individual ./gradlew invocations.
#
# Usage:
#   scripts/build.sh                       # clean build: compile, test, package
#   scripts/build.sh --skip-tests          # skip unit tests/Checkstyle/SpotBugs
#   scripts/build.sh --install /path/to/server/plugins
#                                           # also copies the built jar there
#   scripts/build.sh -h | --help
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"

SKIP_TESTS=false
INSTALL_DIR=""

usage() {
    grep '^#' "$0" | sed -e 's/^#!.*//' -e 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        --install)
            if [ $# -lt 2 ]; then
                echo "error: --install requires a path argument" >&2
                exit 1
            fi
            INSTALL_DIR="$2"
            shift 2
            ;;
        -h|--help)
            usage 0
            ;;
        *)
            echo "error: unknown argument '$1'" >&2
            usage 1
            ;;
    esac
done

cd "$REPO_ROOT"

GRADLE_ARGS=(clean build)
if [ "$SKIP_TESTS" = true ]; then
    GRADLE_ARGS+=(-x test -x checkstyleMain -x checkstyleTest -x spotbugsMain -x spotbugsTest)
    echo "==> Building RigelMCMod (tests/Checkstyle/SpotBugs skipped)..."
else
    echo "==> Building RigelMCMod (compile, test, Checkstyle, SpotBugs, package)..."
fi

set +e
./gradlew "${GRADLE_ARGS[@]}"
GRADLE_EXIT=$?
set -e

if [ "$GRADLE_EXIT" -ne 0 ]; then
    echo "" >&2
    echo "error: Gradle build failed (exit code $GRADLE_EXIT)." >&2
    echo "" >&2
    echo "If the output above mentions a timeout downloading" >&2
    echo "services.gradle.org, that's the wrapper's own one-time bootstrap download," >&2
    echo "not a problem with this project. Things to try:" >&2
    echo "  1. Just retry - transient network blips happen." >&2
    echo "  2. Test connectivity to that host directly:" >&2
    echo "       curl -I https://services.gradle.org/distributions/gradle-9.6.1-bin.zip" >&2
    echo "  3. If you have Gradle installed separately, bypass the wrapper entirely:" >&2
    echo "       gradle ${GRADLE_ARGS[*]}" >&2
    echo "  4. If you're behind a proxy, point the wrapper at it:" >&2
    echo "       GRADLE_OPTS=\"-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>\" $0" >&2
    exit "$GRADLE_EXIT"
fi

JAR_PATH="$(find "$REPO_ROOT/plugin/build/libs" -maxdepth 1 -name 'RigelMCMod-*.jar' -print -quit)"

if [ -z "$JAR_PATH" ]; then
    echo "error: build succeeded but no RigelMCMod-*.jar was found in plugin/build/libs/" >&2
    exit 1
fi

echo "==> Build succeeded: $JAR_PATH"

if [ -n "$INSTALL_DIR" ]; then
    mkdir -p "$INSTALL_DIR"
    cp "$JAR_PATH" "$INSTALL_DIR/"
    echo "==> Installed to $INSTALL_DIR/$(basename "$JAR_PATH")"
    echo "    Restart (or /reload, though a full restart is safer) your test server to pick it up."
fi
