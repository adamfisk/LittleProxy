#!/usr/bin/env bash
function die() {
  echo $*
  exit 1
}

mvn clean || die "Could not clean?"
mvn package || die "Could not package proxy"
java -jar target/littleproxy-0.1.jar $* || die "Java process exited abnormally"
