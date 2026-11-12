#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# 本机默认 java 是 1.8，Spring Boot 3 需要 17+，这里显式指定。
# 想换 JDK：CLS_JAVA_HOME=/path/to/jdk ./run.sh
export JAVA_HOME="${CLS_JAVA_HOME:-/opt/homebrew/opt/openjdk}"

mkdir -p data
# 用 mvnw：Maven 版本锁在仓库里，不受本机装的是 3.5.9 还是 3.9.x 影响。
[[ -x ./mvnw ]] || chmod +x ./mvnw
exec ./mvnw -q spring-boot:run
