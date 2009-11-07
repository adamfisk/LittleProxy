#!/usr/bin/env bash

RELEASE_VERSION=$1
svn copy "http://svn.littleshoot.org/svn/littleproxy/trunk" "http://svn.littleshoot.org/svn/littleproxy/tags/littleproxy-${RELEASE_VERSION}" -m "Tag for LittleProxy release ${RELEASE_VERSION}"
