#!/usr/bin/env bash
function die() {
  echo $*
  exit 1
}

mvn clean || die "Could not clean?"
mvn package -Dmaven.test.skip=true || die "Could not package proxy"
javaArgs="-Xmx400m -jar target/littleproxy-0.1.jar $*"
java6Path=/System/Library/Frameworks/JavaVM.framework/Versions/1.6/Home/bin/java

if [ -f "$java6Path" ]
then
    echo "Running with Java 6 on OSX"
    /System/Library/Frameworks/JavaVM.framework/Versions/1.6/Home/bin/java $javaArgs || die "Java process exited abnormally"
else
    echo "Running using Java on path at `which java`"
    java $javaArgs || die "Java process exited abnormally"
fi
