package de.sawtschuk.hc2garmin.work

import kotlinx.coroutines.sync.Mutex

/** Serializes manual, historical, and periodic uploads within the app process. */
object SyncCoordinator {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
