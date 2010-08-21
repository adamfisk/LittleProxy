#!/usr/bin/env bash

ARGS=1  # One arg to script expected.

if [ $# -ne "$ARGS" ]
then
  echo "Must include the version number"
  exit 1
fi

RELEASE_VERSION=$1
svn copy "http://svn.littleshoot.org/svn/littleproxy/trunk" "http://svn.littleshoot.org/svn/littleproxy/tags/littleproxy-${RELEASE_VERSION}" -m "Tag for LittleProxy release ${RELEASE_VERSION}"
