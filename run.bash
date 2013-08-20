#!/usr/bin/env bash
function die() {
  echo $*
  exit 1
}

mvn package -Dmaven.test.skip=true || die "Could not package"

fullPath=`dirname $0`
jar=`find $fullPath/target/littleproxy*-littleproxy-shade.jar`
cp=`echo $jar | sed 's,./,'$fullPath'/,'`
javaArgs="-server -XX:+HeapDumpOnOutOfMemoryError -Xmx800m -jar "$cp" $*"

echo "Running using Java on path at `which java` with args $javaArgs"
java $javaArgs || die "Java process exited abnormally"
