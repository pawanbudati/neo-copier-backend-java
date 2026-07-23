#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Start Up Batch script for Unix/Linux
# ----------------------------------------------------------------------------

DIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_DIR="$DIR/.mvn/apache-maven-3.9.9"

if [ ! -f "$MAVEN_DIR/bin/mvn" ]; then
    echo "Downloading Apache Maven 3.9.9..."
    mkdir -p "$DIR/.mvn"
    curl -sSL "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" | tar -xz -C "$DIR/.mvn/"
fi

chmod +x "$MAVEN_DIR/bin/mvn"
exec "$MAVEN_DIR/bin/mvn" "$@"
