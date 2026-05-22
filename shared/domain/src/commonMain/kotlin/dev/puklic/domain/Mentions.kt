package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId

data class MessageMentions(
    val users: List<UserId>,
    val roles: List<RoleId>,
    val channels: List<ChannelId>,
    val everyone: Boolean,
)

sealed interface MentionTarget {
    data class User(val id: UserId) : MentionTarget
    data class Role(val id: RoleId) : MentionTarget
    data class Channel(val id: ChannelId) : MentionTarget
    data object Everyone : MentionTarget
    data object Here : MentionTarget
}
