package dev.puklic.protocol.discord.mapper

import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.dto.DiscordUserDto

internal fun DiscordUserDto.toDomain(): UserSummary =
    UserSummary(
        id = UserId(id.toLong()),
        username = username,
        globalName = globalName,
        discriminator = discriminator?.takeIf { it != "0" },
        avatarHash = avatar,
        bot = bot,
        system = system,
    )
