# ---------- 构建阶段 ----------
# 基础镜像做成参数：国内直连 Docker Hub 会 TLS 握手超时（实测过），
# 但写死在 Dockerfile 里又会让 CI 或海外机器变慢。用 ARG 让两边都不改文件。
#   国内构建：docker build --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 \
#                          --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre .
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /src

# 容器内没有宿主机的 ~/.m2/settings.xml，不配镜像会从 Maven Central 拉，
# 在国内这个阶段能卡好几分钟甚至超时。阿里云仓库对海外也可达，所以作为默认。
COPY docker/maven-settings.xml /root/.m2/settings.xml

# 先只拷 pom 拉依赖：改代码不会让依赖层缓存失效，重复构建能快一个数量级
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
# 刻意不用 alpine：sqlite-jdbc 是 JNI，靠打包在 jar 里的 .so 加载本地库。
# alpine 是 musl libc，与 glibc 的 .so 不兼容，容易出现「容器里能启动、
# 一查库就 UnsatisfiedLinkError」。多 100MB 换掉这个不确定性，值得。
FROM ${JRE_IMAGE}

# 时区：容器默认 UTC，不设的话日志和「今天」的日期都会差 8 小时，
# 排查「凌晨那次抓取为什么失败」时会直接看错日子。
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

# 非 root 运行。数据目录要提前建好并交给这个用户，否则挂载卷后无写权限。
# curl 只为了 HEALTHCHECK：Ubuntu 基础镜像默认不带 curl/wget，不装的话
# 健康检查永远是 unhealthy，容器会被反复重启。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app \
    && mkdir -p /app/data && chown -R app:app /app

WORKDIR /app
COPY --from=build --chown=app:app /src/target/cls-news-0.1.0.jar /app/app.jar

USER app

ENV DB_PATH=/app/data/news.db \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

EXPOSE 8080

# 用 /healthz 而不是 /api/status：后者要鉴权，健康检查得带凭据才能过，
# 一旦改密码就会把容器判死并反复重启。
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/healthz > /dev/null || exit 1

# exec 形式，让 java 成为 PID 1，能收到 docker stop 的 SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
