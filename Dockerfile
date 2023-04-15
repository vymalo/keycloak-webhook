ARG TAG=21.0.2
ARG PLUGIN_VERSION=0.1.0

FROM curlimages/curl AS DOWNLOADER

WORKDIR /app

ENV WEBHOOK_PLUGIN_VERSION=$PLUGIN_VERSION

RUN curl -H "Accept: application/zip" https://github.com/vymalo/keycloak-webhook/releases/download/v${WEBHOOK_PLUGIN_VERSION}/keycloak-webhook-${WEBHOOK_PLUGIN_VERSION}-all.jar -o /app/keycloak-webhook-${WEBHOOK_PLUGIN_VERSION}.jar -Li


FROM quay.io/keycloak/keycloak:${TAG}

ENV WEBHOOK_PLUGIN_VERSION=$PLUGIN_VERSION
ENV KEYCLOAK_DIR /opt/keycloak
ENV KC_PROXY edge

LABEL maintainer="Stephane, Segning Lambou <selastlambou@gmail.com>"

USER 0

RUN mkdir $JBOSS_HOME/providers

COPY --from=DOWNLOADER /app/keycloak-webhook-${WEBHOOK_PLUGIN_VERSION}.jar $JBOSS_HOME/providers/

RUN $KEYCLOAK_DIR/bin/kc.sh build

USER 1000