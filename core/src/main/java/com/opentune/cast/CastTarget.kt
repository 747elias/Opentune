package com.opentune.cast

/** A device music can be cast to. */
data class CastTarget(
    val id: String,
    val name: String,
    val kind: Kind,
    val host: String,
    val port: Int = 0,
    val model: String? = null,
    val dlna: DlnaDevice? = null,
) {
    enum class Kind { CHROMECAST, DLNA }
}
