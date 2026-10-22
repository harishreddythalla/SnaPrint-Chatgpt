package com.snaptoprint

import org.json.JSONObject

data class EventSession(
    val id: String,
    val name: String,
    val startedAtMs: Long,
    val active: Boolean,
    val status: String,
    val baselineCutoffSec: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sessionId", id)
        .put("name", name)
        .put("startedAtMs", startedAtMs)
        .put("active", active)
        .put("status", status)
}

data class MediaRow(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val size: Long,
    val isPending: Boolean,
)

data class QueuedPhoto(
    val photoId: String,
    val sessionId: String,
    val eventName: String,
    val sessionStartedAtMs: Long,
    val sequence: Int,
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val captureTimeMs: Long,
    val size: Long,
    val sha256: String,
    val stagedPath: String,
    val state: String,
) {
    fun metadataJson(): JSONObject = JSONObject()
        .put("photoId", photoId)
        .put("sessionId", sessionId)
        .put("eventName", eventName)
        .put("sessionStartedAtMs", sessionStartedAtMs)
        .put("sequence", sequence)
        .put("fileName", displayName)
        .put("mimeType", mimeType)
        .put("captureTimeMs", captureTimeMs)
        .put("size", size)
        .put("sha256", sha256)
}
