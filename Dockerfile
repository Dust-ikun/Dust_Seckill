# =============================================================================
#  后端（Spring Boot 4 / Java 21）镜像 —— 多阶段构建
#
#  【为什么是两阶段】第一阶段用带 Maven 的 JDK 镜像编译出 jar；第二阶段只把 jar
#  拷进一个纯 JRE 镜像。最终镜像里没有源码、没有 Maven、没有 JDK 编译器，
#  体积小一个数量级，且别人 `docker compose build` 时不需要本机装 JDK/Maven。
#
#  【缓存设计】先只拷 pom.xml 执行 dependency:go-offline，再拷 src。
#  Docker 的层缓存按「输入是否变化」判断：只改业务代码时 pom.xml 没变，
#  依赖下载层直接命中缓存，重复构建只有编译这一步的耗时。
#
#  【为什么装了 curl】healthcheck 需要真的请求 /actuator/health（带 DB/Redis
#  连通性检查，DOWN 时返回 503），bash /dev/tcp 那种「端口通就算健康」的判据
#  探不出「进程活着但连不上数据库」的状态。eclipse-temurin 的 Ubuntu 基底不带 curl。
# =============================================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷 pom.xml 单独拉依赖：只改业务代码时这一层持续命中缓存
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl 仅供 healthcheck 使用
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# jar 名由 pom.xml 的 artifactId+version 决定（seckill-0.0.1-SNAPSHOT.jar）
COPY --from=build /build/target/seckill-0.0.1-SNAPSHOT.jar /app/app.jar

ENV TZ=Asia/Shanghai

# 8081 业务端口 / 9091 actuator 管理端口
EXPOSE 8081 9091

# JAVA_OPTS 由 compose 注入（如 -XX:MaxRAMPercentage=70.0），未设置时为空
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
