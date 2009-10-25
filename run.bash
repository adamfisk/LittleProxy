#!/usr/bin/env bash
mvn clean
mvn package
cd target
java -jar littleproxy-0.1.jar
