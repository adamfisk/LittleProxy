#!/usr/bin/env bash
function die() {
  echo $*
  exit 1
}

mvn test-compile exec:java -Dexec.mainClass="org.littleshoot.proxy.PerformanceServer" -Dexec.classpathScope="test"