#!/bin/sh
set -eu

repository_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$repository_directory/gradlew" verifyAll "$@"
