#!/bin/sh
# Keep Maven, Lombok and the application on the project's supported JDK.
set -eu

is_jdk17() {
    [ -x "$1/bin/java" ] && [ -x "$1/bin/javac" ] || return 1
    case "$("$1/bin/javac" -version 2>&1)" in
        'javac 17'|'javac 17.'*) return 0 ;;
        *) return 1 ;;
    esac
}

selected_java_home=
select_jdk() {
    if [ -z "$selected_java_home" ] && is_jdk17 "$1"; then
        selected_java_home=$1
    fi
}

if [ -n "${JAVA_HOME:-}" ]; then
    select_jdk "$JAVA_HOME"
fi
if [ -x /usr/libexec/java_home ]; then
    detected_home=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
    if [ -n "$detected_home" ]; then
        select_jdk "$detected_home"
    fi
fi
for candidate in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
                 /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
                 /usr/lib/jvm/*; do
    select_jdk "$candidate"
done
if [ -z "$selected_java_home" ] && command -v java >/dev/null 2>&1; then
    detected_home=$(java -XshowSettings:properties -version 2>&1 |
        sed -n 's/^[[:space:]]*java.home = //p')
    if [ -n "$detected_home" ]; then
        select_jdk "$detected_home"
    fi
fi

if [ -z "$selected_java_home" ]; then
    echo 'BootFS requires JDK 17. Install it and set JAVA_HOME to its home directory.' >&2
    exit 1
fi
export JAVA_HOME="$selected_java_home"
export PATH="$JAVA_HOME/bin:$PATH"
if ! command -v mvn >/dev/null 2>&1; then
    echo 'Maven is required. Install Maven and ensure mvn is on PATH.' >&2
    exit 1
fi
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
echo "Starting BootFS with JDK 17: $JAVA_HOME" >&2
exec mvn spring-boot:run -Dspring-boot.run.profiles=dev "$@"
