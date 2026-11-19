package com.opentune.data.together

/** Shared settings and state for the Android and desktop listening room clients. */
object RoomProtocol {
    const val KIND = 21420
    val RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.mom",
        "wss://relay.nostr.net",
        "wss://nostr-pub.wellorder.net",
        "wss://offchain.pub",
    )

    enum class Phase { Connecting, FindingHost, Waiting, Live, LostHost, Ended }

    data class ChatLine(
        val name: String,
        val text: String,
        val mine: Boolean = false,
        val system: Boolean = false,
        val at: Long = System.currentTimeMillis(),
        val fromId: String? = null,
        /** A voice note or a piece of talk, instead of text. */
        val voice: VoiceClip? = null,
    )

    /** A voice clip as it arrives: AAC frames with the codec's setup, see [VoiceFrames]. */
    class VoiceClip(val id: String, val cfg: ByteArray?, val frames: List<ByteArray>, val ms: Long, val complete: Boolean, val live: Boolean)

    data class Room(
        val code: RoomCode,
        val hosting: Boolean,
        val myName: String,
        val phase: Phase = Phase.Connecting,
        val relays: Int = 0,
        val members: List<Member> = emptyList(),
        val hostName: String? = null,
        val open: Boolean = false,
        val chat: List<ChatLine> = emptyList(),
        val track: Track? = null,
        val hostPrivate: Boolean = false,
        /** A guest who paused for themselves; the room plays on without them. */
        val holding: Boolean = false,
        val endedReason: String? = null,
        /** This phone's id in [members]. */
        val myId: String? = null,
        /** For the host: people who knocked, waiting to be let in or turned away. */
        val requests: List<Member> = emptyList(),
        /** For the host: knocking needs approval; off lets anyone with the code straight in. */
        val askToJoin: Boolean = true,
        /** Member ids of the people talking right now (others; without this client's own voice). */
        val talking: List<String> = emptyList(),
    ) {
        val following get() = !hosting && phase == Phase.Live
    }

    /** A playback control pressed on a guest's phone. */
    sealed interface Control {
        data object PlayPause : Control
        data object Next : Control
        data object Previous : Control
        data class Seek(val positionMs: Long) : Control
    }
}
