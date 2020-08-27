#!/bin/bash

BIN_NAME=target/testrunner

$MAVEN_HOME/bin/mvn clean compile assembly:single

cat > $BIN_NAME <<'STAN'
#!/bin/sh
MYSELF=`which "$0" 2>/dev/null`
[ $? -gt 0 -a -f "$0" ] && MYSELF="./$0"
java=java
if test -n "$JAVA_HOME"; then
    java="$JAVA_HOME/bin/java"
fi
exec "$java" $java_args -jar $MYSELF "$@"
exit 1 
STAN

JAR=$(ls target | grep testrunner-.*-jar-with-dependencies.jar | head -1)
cat target/$JAR >> $BIN_NAME
chmod +x $BIN_NAME
