package dev.pi.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The remembered Pi host. The base URL doubles as the id: one phone talks to
 * one host per saved profile, and the PIN is stored separately in the Keystore.
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val pinSaved: Boolean = false,
    val lastCwd: String? = null,
    val lastUsedAt: Long = 0L,
)

@Serializable
data class ProfilesFile(
    val version: Int = 1,
    val selectedProfileId: String? = null,
    val profiles: List<ServerProfile> = emptyList(),
) {
    fun selected(): ServerProfile? = profiles.firstOrNull { it.id == selectedProfileId }
}

object ProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun decode(raw: String): ProfilesFile? =
        runCatching { json.decodeFromString<ProfilesFile>(raw) }.getOrNull()

    fun encode(file: ProfilesFile): String = json.encodeToString(ProfilesFile.serializer(), file)
}
