package com.nexusagent.agent.perception

/**
 * A downscaled, JPEG-compressed frame ready to attach to a model request.
 *
 * Public because it crosses the module boundary via [PerceptionRepository], while the
 * capture machinery itself stays internal.
 */
data class Screenshot(
    val base64Jpeg: String,
    val width: Int,
    val height: Int,
    val bytes: Int,
)
