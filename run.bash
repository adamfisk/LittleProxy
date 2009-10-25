#!/usr/bin/env bash
mvn clean
mvn package
cd target
jav -jar littleproxy-0.1.jar
