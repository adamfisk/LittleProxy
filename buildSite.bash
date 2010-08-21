#!/usr/bin/env bash

mvn site
cp -R target/site/* ~/littleshoot/trunk/server/appengine/static/littleproxy/
