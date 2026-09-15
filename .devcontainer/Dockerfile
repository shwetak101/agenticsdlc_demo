FROM maven:3.9.9-eclipse-temurin-21

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash ca-certificates git python3 util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --shell /bin/bash vscode \
    && mkdir -p /workspaces /home/vscode/.m2 \
    && chown vscode:vscode /workspaces /home/vscode/.m2

ENV MAVEN_CONFIG=/home/vscode/.m2
USER vscode
