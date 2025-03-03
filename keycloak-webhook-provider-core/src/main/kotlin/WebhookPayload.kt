@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package com.vymalo.keycloak.webhook

import com.google.gson.annotations.SerializedName

data class WebhookPayload(

    /* I've just picked some types from `org.keycloak.events.EventType` plus some for admin types  */
    @SerializedName("type")
    val type: kotlin.String,

    @SerializedName("realmId")
    val realmId: kotlin.String,

    @SerializedName("id")
    val id: kotlin.String? = null,

    @SerializedName("time")
    val time: java.math.BigDecimal? = null,

    @SerializedName("clientId")
    val clientId: kotlin.String? = null,

    @SerializedName("userId")
    val userId: kotlin.String? = null,

    @SerializedName("ipAddress")
    val ipAddress: kotlin.String? = null,

    @SerializedName("error")
    val error: kotlin.String? = null,

    @SerializedName("details")
    val details: kotlin.collections.Map<kotlin.String, kotlin.Any>? = null,

    @SerializedName("resourcePath")
    val resourcePath: kotlin.String? = null,

    @SerializedName("representation")
    val representation: kotlin.String? = null

)