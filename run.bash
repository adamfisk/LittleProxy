#!/usr/bin/env bash
function die()
{
  echo $*
  exit 1
}

mvn clean
mvn package || die "Could not package proxy"
cd target || die "No target dir?"
java -jar littleproxy-0.1.jar $* || die "Java process exited abnormally"
