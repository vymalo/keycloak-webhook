ARG TAG=23.0.6

FROM quay.io/keycloak/keycloak:${TAG}

ENV WEBHOOK_PLUGIN_VERSION 0.5.0

ENV KEYCLOAK_DIR /opt/keycloak
ENV KC_PROXY edge

LABEL maintainer="Stephane, Segning Lambou <selastlambou@gmail.com>"

USER 0

COPY build/libs/keycloak-webhook-${WEBHOOK_PLUGIN_VERSION}-all.jar $KEYCLOAK_DIR/providers/keycloak-webhook-all.jar

RUN $KEYCLOAK_DIR/bin/kc.sh build

USER 1000