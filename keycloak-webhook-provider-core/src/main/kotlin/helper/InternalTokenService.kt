package com.vymalo.keycloak.webhook.core.helper

import org.keycloak.common.util.Time
import org.keycloak.models.ClientModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.utils.KeycloakModelUtils
import org.keycloak.protocol.oidc.TokenManager.AccessTokenResponseBuilder
import org.keycloak.representations.AccessToken
import org.keycloak.services.Urls

object InternalTokenService {
    fun createBearerToken(
        session: KeycloakSession,
        realm: RealmModel,
        client: ClientModel,
        audience: String,
        ttlSeconds: Long
    ): String {
        val token = AccessToken().apply {
            id(KeycloakModelUtils.generateId())
            type("Bearer")
            issuedNow()
            subject(client.id)
            issuedFor(client.clientId)
            issuer(Urls.realmIssuer(session.context.uri.baseUri, realm.name))
            audience(audience)
            exp(Time.currentTime().toLong() + ttlSeconds)
        }

        return session.tokens().encode(token)
    }
}
