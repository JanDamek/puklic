package dev.puklic.android

import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.persistence.sqldelight.ChannelRepositoryImpl
import dev.puklic.persistence.sqldelight.GuildRepositoryImpl
import dev.puklic.persistence.sqldelight.MessageRepositoryImpl
import dev.puklic.persistence.sqldelight.OutboundQueueImpl
import dev.puklic.persistence.sqldelight.ReadStateRepositoryImpl
import dev.puklic.persistence.sqldelight.UserPreferencesRepositoryImpl
import dev.puklic.persistence.sqldelight.UserRepositoryImpl
import kotlinx.coroutines.Dispatchers

/** SQLDelight-backed repositories shared by every session of the app, all on the IO dispatcher. */
internal class AndroidStores(database: PuklicDatabase) {
    private val io = Dispatchers.IO
    private val nowMs: () -> Long = System::currentTimeMillis

    val messages = MessageRepositoryImpl(database, io, nowMs)
    val guilds = GuildRepositoryImpl(database, io, nowMs)
    val channels = ChannelRepositoryImpl(database, io, nowMs)
    val users = UserRepositoryImpl(database, io, nowMs)
    val outboundQueue = OutboundQueueImpl(database, io)
    val readState = ReadStateRepositoryImpl(database, io)
    val preferences: UserPreferencesRepository = UserPreferencesRepositoryImpl(database, io)
}
