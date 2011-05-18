#!/usr/bin/env bash

ab -n 1000 -c 100 -X 127.0.0.1:8080 http://issues.littleshoot.org:8080/favicon.ico
