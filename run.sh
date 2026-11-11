#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# 本机默认 java 是 1.8，Spring Boot 3 需要 17+，这里显式指定。
# 想换 JDK：CLS_JAVA_HOME=/path/to/jdk ./run.sh
export JAVA_HOME="${CLS_JAVA_HOME:-/opt/homebrew/opt/openjdk}"

mkdir -p data
exec mvn -q spring-boot:run
