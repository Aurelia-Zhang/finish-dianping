# 直接使用本地编译好的 jar 包运行（不在 Docker 里重新编译）
FROM eclipse-temurin:8-jre
WORKDIR /app

# 从本地 target 目录复制 jar 包
COPY target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar

# 暴露端口
EXPOSE 8081

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
