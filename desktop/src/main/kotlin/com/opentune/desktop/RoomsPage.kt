package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.data.together.RoomCode
import com.opentune.data.together.RoomProtocol.Phase
import com.opentune.data.together.RoomProtocol.Room

@Composable
internal fun RoomsPage() {
    val room by DesktopRooms.room.collectAsState()
    val invite by DesktopRooms.invite.collectAsState()
    val picks by DesktopRooms.picksPlayNow.collectAsState()
    val active = room
    val incomingInvite = invite
    var name by remember { mutableStateOf(DesktopRooms.savedName) }
    var input by remember { mutableStateOf("") }
    var askToJoin by remember { mutableStateOf(DesktopRooms.askToJoin) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(incomingInvite) { incomingInvite?.let { input = it.pretty } }
    LaunchedEffect(active?.code) { notice = null }
    LaunchedEffect(Unit) { DesktopRooms.notices.collect { notice = it } }

    ScrollPage("Listen together", spacing = 16.dp, trailing = {
        if (active != null && active.phase != Phase.Ended) {
            val action = if (active.hosting) "End room" else "Leave room"
            OutlinedButton(onClick = DesktopRooms::leave) { Text(action) }
        }
    }) {
        if (active == null || active.phase == Phase.Ended || incomingInvite != null && incomingInvite != active.code) {
            item("join") {
                RoomCard {
                    active?.endedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (incomingInvite != null && active?.phase != Phase.Ended && active != null) {
                        Text("Joining this invitation will leave your current room.")
                    }
                    OutlinedTextField(name, { name = it.take(30) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(input, { input = it.take(512) }, label = { Text("Room code or invite link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val code = RoomCode.parse(input) ?: RoomCode.find(input)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { code?.let { DesktopRooms.join(it, name) } }, enabled = code != null && name.isNotBlank()) {
                            Text("Join room")
                        }
                        if (active == null || active.phase == Phase.Ended) {
                            OutlinedButton(onClick = { DesktopRooms.create(name) }, enabled = name.isNotBlank()) {
                                Text("Start a room")
                            }
                        }
                        if (incomingInvite != null) TextButton(onClick = DesktopRooms::dismissInvite) { Text("Dismiss invite") }
                    }
                    if (active == null || active.phase == Phase.Ended) {
                        RoomToggle("Approve guests before they join", askToJoin, change = {
                            askToJoin = it
                            DesktopRooms.setAskToJoin(it)
                        })
                    }
                }
            }
        } else {
            item("status") { RoomStatus(active, picks) { notice = it } }
            if (active.phase == Phase.Live) {
                item("members") { RoomMembers(active) }
                item("voice") { RoomVoiceControls() }
                item("chat") { RoomChat(active) }
            }
        }
        notice?.let { text ->
            item("notice") {
                Text(text, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = GUTTER))
            }
        }
    }
}

@Composable
private fun RoomStatus(room: Room, picks: Boolean, notice: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var qr by remember(room.code) { mutableStateOf(false) }
    RoomCard {
        val status = when (room.phase) {
            Phase.Connecting -> "Connecting to relays…"
            Phase.FindingHost -> "Looking for the host…"
            Phase.Waiting -> "Waiting for the host to approve you…"
            Phase.Live -> if (room.holding) "Playback paused for you" else "Listening together"
            Phase.LostHost -> "Reconnecting to the host…"
            Phase.Ended -> "Room ended"
        }
        Text(status, style = MaterialTheme.typography.titleLarge)
        room.hostName?.let { Text("Hosted by $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        room.track?.let { track ->
            Text(track.title, fontWeight = FontWeight.SemiBold)
            Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (room.hostPrivate) Text("The host is playing a track that can't be shared.")
        Text(room.code.pretty, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(room.code.pretty))
                notice("Room code copied")
            }) { Text("Copy code") }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(room.code.link))
                notice("Invite link copied")
            }) { Text("Copy link") }
            TextButton(onClick = { qr = !qr }) { Text(if (qr) "Hide QR code" else "Show QR code") }
        }
        if (qr) QrCode(room.code.link, 176.dp)
        if (room.hosting) {
            RoomToggle("Approve guests before they join", room.askToJoin, DesktopRooms::setAskToJoin)
            RoomToggle("Let guests control playback", room.open, DesktopRooms::setOpen)
        } else if (room.phase == Phase.Live) {
            RoomToggle("Play my picks for everyone", picks, DesktopRooms::setPicksPlayNow, enabled = room.open)
        }
    }
}

@Composable
private fun RoomMembers(room: Room) {
    RoomCard {
        Text("In this room", style = MaterialTheme.typography.titleLarge)
        for (member in room.members) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val tags = listOfNotNull("You".takeIf { member.id == room.myId }, "Host".takeIf { member.host }, "Speaking".takeIf { member.id in room.talking })
                val label = (listOf(member.name) + tags).joinToString(" · ")
                Text(label, modifier = Modifier.weight(1f))
                if (room.hosting && !member.host) TextButton(onClick = { DesktopRooms.remove(member.id) }) { Text("Remove") }
            }
        }
        if (room.requests.isNotEmpty()) Text("Waiting to join", style = MaterialTheme.typography.titleMedium)
        for (member in room.requests) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(member.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { DesktopRooms.reject(member.id) }) { Text("Decline") }
                OutlinedButton(onClick = { DesktopRooms.approve(member.id) }) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun RoomChat(room: Room) {
    var message by remember(room.code) { mutableStateOf("") }
    val playingClip by DesktopRooms.playingClip.collectAsState()
    val send = {
        if (message.isNotBlank()) {
            DesktopRooms.say(message)
            message = ""
        }
    }
    RoomCard {
        Text("Room chat", style = MaterialTheme.typography.titleLarge)
        for (line in room.chat) {
            if (line.system) {
                Text(line.text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else if (line.voice != null) {
                val clip = line.voice
                val who = if (line.mine) "You" else line.name
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$who · Voice note", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = { DesktopRooms.playClip(clip) },
                        enabled = clip.cfg != null && clip.frames.isNotEmpty(),
                    ) { Text(if (playingClip == clip.id) "Stop" else "Play") }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (line.mine) "You" else line.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(line.text)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                message, { message = it.take(300) }, label = { Text("Message") }, singleLine = true,
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { send(); true } else false
                },
            )
            Button(onClick = send, enabled = message.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun RoomVoiceControls() {
    val mode by DesktopRooms.recording.collectAsState()
    val muted by DesktopRooms.callMuted.collectAsState()
    val talking by DesktopRooms.speaking.collectAsState()
    val talkMuted by DesktopRooms.talkMuted.collectAsState()
    RoomCard {
        Text("Room voice", style = MaterialTheme.typography.titleLarge)
        RoomToggle("Hear live talk", !talkMuted, { DesktopRooms.setTalkMuted(!it) })
        when (mode) {
            null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { DesktopRooms.startVoice(RoomVoiceMode.Note) }) { Text("Record voice note") }
                    OutlinedButton(onClick = { DesktopRooms.startVoice(RoomVoiceMode.Talk) }) { Text("Start talking") }
                }
                TextButton(onClick = { DesktopRooms.startVoice(RoomVoiceMode.Call) }) { Text("Join call") }
            }
            RoomVoiceMode.Call -> {
                Text(if (muted) "Microphone muted" else if (talking) "You're speaking" else "Microphone on")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { DesktopRooms.setCallMuted(!muted) }) { Text(if (muted) "Unmute" else "Mute") }
                    TextButton(onClick = DesktopRooms::stopVoice) { Text("Leave call") }
                }
            }
            RoomVoiceMode.Note -> Button(onClick = DesktopRooms::stopVoice) { Text("Stop and send voice note") }
            RoomVoiceMode.Talk -> Button(onClick = DesktopRooms::stopVoice) { Text("Stop talking") }
        }
    }
}

@Composable
private fun RoomToggle(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun RoomCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
