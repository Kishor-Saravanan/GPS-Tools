#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Licensed under the Apache License, Version 2.0
#

# Gradle Wrapper script
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Resolve the directory of this script
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then PRG="$link"
    else PRG=$(dirname "$PRG")"/$link"
    fi
done
SAVED=$(pwd)
cd $(dirname "$PRG") >/dev/null
APP_HOME=$(pwd -P)
cd "$SAVED" >/dev/null

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine OS
case "$(uname -s)" in
    CYGWIN*|MINGW*|MSYS*) APP_HOME=$(cygpath --path --mixed "$APP_HOME"); CLASSPATH=$(cygpath --path --mixed "$CLASSPATH");;
esac

# Find Java
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then JAVACMD="$JAVA_HOME/jre/sh/java"
    else JAVACMD="$JAVA_HOME/bin/java"
    fi
else
    JAVACMD="java"
fi

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
