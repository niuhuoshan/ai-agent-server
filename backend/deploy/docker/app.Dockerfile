ARG JAVA_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JAVA_IMAGE}

RUN groupadd --gid 10001 agent && useradd --uid 10001 --gid 10001 --create-home agent \
    && mkdir -p /opt/nhs/data /opt/nhs/logs /opt/nhs/temp \
    && chown -R agent:agent /opt/nhs

WORKDIR /opt/nhs
COPY --chown=agent:agent nhs-admin.jar /opt/nhs/app.jar

USER 10001:10001
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS=""
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=4s --retries=18 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["sh", "-c", "exec java -XX:+UseZGC -XX:+HeapDumpOnOutOfMemoryError $JAVA_OPTS -jar /opt/nhs/app.jar"]
