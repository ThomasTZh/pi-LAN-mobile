package dev.pi.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Atomic JSON store for the remembered host. A corrupted file is quarantined
 * and replaced with an empty state instead of crashing the app, and every write
 * goes through a temp file so a killed process cannot leave half a profile.
 */
class ProfileStore(context: Context) {
    private val file = File(context.filesDir, "profiles.json")
    private val _state = MutableStateFlow(ProfilesFile())
    val state: StateFlow<ProfilesFile> = _state.asStateFlow()

    private var loaded = false

    suspend fun ensureLoaded(): ProfilesFile = withContext(Dispatchers.IO) {
        if (!loaded) {
            _state.value = readFile()
            loaded = true
        }
        _state.value
    }

    private fun readFile(): ProfilesFile {
        if (!file.isFile) return ProfilesFile()
        return try {
            ProfileCodec.decode(file.readText()) ?: quarantineAndReset()
        } catch (_: Exception) {
            quarantineAndReset()
        }
    }

    private fun quarantineAndReset(): ProfilesFile {
        runCatching {
            file.renameTo(File(file.parentFile, "profiles.json.corrupt-${System.currentTimeMillis()}"))
        }
        return ProfilesFile()
    }

    private fun write(next: ProfilesFile) {
        val temp = File(file.parentFile, "profiles.json.tmp")
        temp.writeText(ProfileCodec.encode(next))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    suspend fun update(transform: (ProfilesFile) -> ProfilesFile): ProfilesFile = withContext(Dispatchers.IO) {
        ensureLoaded()
        val next = transform(_state.value)
        write(next)
        _state.value = next
        next
    }

    suspend fun upsert(profile: ServerProfile): ProfilesFile = update { current ->
        val index = current.profiles.indexOfFirst { it.id == profile.id }
        val profiles = if (index >= 0) {
            current.profiles.toMutableList().also { it[index] = profile }
        } else {
            current.profiles + profile
        }
        current.copy(profiles = profiles, selectedProfileId = profile.id)
    }

    suspend fun select(id: String, now: Long = System.currentTimeMillis()): ProfilesFile = update { current ->
        current.copy(
            profiles = current.profiles.map { if (it.id == id) it.copy(lastUsedAt = now) else it },
            selectedProfileId = id,
        )
    }

    suspend fun markPinSaved(id: String, saved: Boolean): ProfilesFile = update { current ->
        current.copy(profiles = current.profiles.map { if (it.id == id) it.copy(pinSaved = saved) else it })
    }

    suspend fun rememberCwd(id: String, cwd: String?): ProfilesFile = update { current ->
        current.copy(profiles = current.profiles.map { if (it.id == id) it.copy(lastCwd = cwd ?: it.lastCwd) else it })
    }

    suspend fun remove(id: String): ProfilesFile = update { current ->
        val profiles = current.profiles.filterNot { it.id == id }
        current.copy(
            profiles = profiles,
            selectedProfileId = if (current.selectedProfileId == id) profiles.firstOrNull()?.id else current.selectedProfileId,
        )
    }

    /** Forget server: profile row only; [SecretStore] clears the sealed PIN. */
    suspend fun clear(): ProfilesFile = update { ProfilesFile() }
}
