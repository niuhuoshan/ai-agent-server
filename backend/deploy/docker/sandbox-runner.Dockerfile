ARG JAVA_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${JAVA_IMAGE}

RUN apk add --no-cache docker-cli \
    && addgroup -g 10001 agent \
    && adduser -D -u 10001 -G agent agent \
    && mkdir -p /opt/agent-runner/data /var/lib/nhs/agent-workspaces \
    && chown -R agent:agent /opt/agent-runner /var/lib/nhs/agent-workspaces

WORKDIR /opt/agent-runner
COPY --chown=agent:agent nhs-sandbox-runner-*.jar /opt/agent-runner/runner.jar

USER 10001:10001
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/agent-runner/runner.jar"]
