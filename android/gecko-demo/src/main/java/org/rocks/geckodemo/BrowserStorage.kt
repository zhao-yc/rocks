package org.rocks.geckodemo

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.rocks.browser.SessionRepository
import org.rocks.browser.core.BrowserData

/** One process-wide IO consumer: an old Activity's final save precedes the next Activity's restore. */
internal class BrowserStorage(private val repository: SessionRepository,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private sealed interface Command {
        data class Read(val reply: CompletableDeferred<Result<BrowserData?>>) : Command
        data class Write(val data: BrowserData) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    val writeFailed = MutableStateFlow(false)
    init {
        scope.launch {
            for (command in commands) when (command) {
                is Command.Read -> command.reply.complete(runCatching { repository.restore() })
                is Command.Write -> { writeFailed.value = runCatching { repository.save(command.data) }.isFailure }
            }
        }
    }
    suspend fun restore(): Result<BrowserData?> {
        val reply = CompletableDeferred<Result<BrowserData?>>()
        commands.send(Command.Read(reply))
        return reply.await()
    }
    fun save(data: BrowserData) { check(commands.trySend(Command.Write(data)).isSuccess) }
}
