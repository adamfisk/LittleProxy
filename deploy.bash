#!/bin/bash

mvn -Dmaven.test.skip=true -DperformRelease=true deploy
