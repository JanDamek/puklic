package dev.puklic.domain

import dev.puklic.ids.UserId

data class UserSummary(
    val id: UserId,
    val username: String,
    val globalName: String?,
    val discriminator: String?,
    val avatarHash: String?,
    val bot: Boolean,
    val system: Boolean,
)
