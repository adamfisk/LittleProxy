#!/usr/bin/env bash

ab -n 1000 -c 100 -X 127.0.0.1:80 http://dev.littleshoot.org:8081/favicon.ico
