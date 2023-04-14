ARG TAG=21.0.2

FROM quay.io/keycloak/keycloak:${TAG}

ENV WEBHOOK_PLUGIN_VERSION 0.1.0
ENV KEYCLOAK_DIR /opt/keycloak
ENV KC_PROXY edge

LABEL maintainer="Stephane, Segning Lambou <selastlambou@gmail.com>"

USER 0

RUN mkdir $JBOSS_HOME/providers

RUN curl -H "Accept: application/zip" https://github.com/vymalo/keycloak-webhook/releases/download/v${WEBHOOK_PLUGIN_VERSION}/keycloak-webhook-${WEBHOOK_PLUGIN_VERSION}-all.jar -o $JBOSS_HOME/providers/keycloak-webhook-${WEBHOOK_PLUGIN_VERSION}.jar -Li

RUN $KEYCLOAK_DIR/bin/kc.sh build

USER 1000