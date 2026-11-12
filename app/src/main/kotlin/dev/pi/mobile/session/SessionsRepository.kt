package dev.pi.mobile.session

import dev.pi.mobile.net.PiSocket
import dev.pi.mobile.proto.arr
import dev.pi.mobile.proto.boolOr
import dev.pi.mobile.proto.firstString
import dev.pi.mobile.proto.intOr
import dev.pi.mobile.proto.longOr
import dev.pi.mobile.proto.obj
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One row of `meta sessions.list`. */
data class SessionRow(
    val path: String,
    val mtimeMs: Long = 0L,
    val size: Long = 0L,
    val sessionId: String? = null,
    val name: String? = null,
    val firstPrompt: String? = null,
    val messageCount: Int = 0,
    val cwd: String? = null,
    val cwdExists: Boolean = true,
) {
    /** Two-line label: explicit name, else the first prompt line. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: firstPrompt?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.take(160)
            ?: "Untitled session"

    /** Short project label for the sessions sheet header. */
    val project: String
        get() = cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Sessions"
}

data class SessionGroup(
    val project: String,
    val cwd: String?,
    val rows: List<SessionRow>,
) {
    val latestMtimeMs: Long get() = rows.maxOfOrNull { it.mtimeMs } ?: 0L
}

data class SessionsState(
    val rows: List<SessionRow> = emptyList(),
    val groups: List<SessionGroup> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * Session list plus grouping. Grouping is a pure function so the sheet can be
 * tested with fixture rows; the repository only owns the meta call.
 */
object SessionGrouping {
    fun parse(data: JsonElement?): List<SessionRow> = when (data) {
        is JsonArray -> parseRows(data)
        is JsonObject -> {
            // The vendored host answers sessions.list as {projects:[{project, sessions}]}.
            val projects = data.arr("projects")
            if (projects != null) {
                projects.flatMap { project ->
                    parseRows((project as? JsonObject)?.arr("sessions"))
                }
            } else {
                parseRows(data.arr("sessions") ?: data.arr("rows"))
            }
        }
        else -> emptyList()
    }

    private fun parseRows(array: JsonArray?): List<SessionRow> = array?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path = obj.firstString("path", "sessionPath", "file") ?: return@mapNotNull null
            SessionRow(
                path = path,
                mtimeMs = obj.longOr("mtimeMs") ?: obj.longOr("mtime") ?: 0L,
                size = obj.longOr("size") ?: 0L,
                sessionId = obj.firstString("sessionId", "id"),
                name = obj.firstString("name", "title"),
                firstPrompt = obj.firstString("firstPrompt", "firstMessage"),
                messageCount = obj.intOr("messageCount") ?: obj.intOr("messages") ?: 0,
                cwd = obj.firstString("cwd", "projectDir"),
                cwdExists = obj.boolOr("cwdExists") ?: true,
            )
        }.orEmpty()

    /**
     * Groups by project directory, newest first inside a group and between
     * groups. Sessions without a cwd fall into one "Sessions" group.
     */
    fun group(rows: List<SessionRow>): List<SessionGroup> {
        if (rows.isEmpty()) return emptyList()
        val sorted = rows.sortedByDescending { it.mtimeMs }
        val groups = LinkedHashMap<String, MutableList<SessionRow>>()
        for (row in sorted) {
            val key = row.cwd?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: ""
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }
        return groups.entries
            .map { (cwd, members) ->
                SessionGroup(
                    project = members.first().project,
                    cwd = cwd.ifEmpty { null },
                    rows = members,
                )
            }
            .sortedByDescending { it.latestMtimeMs }
    }
}

class SessionsRepository(private val connection: PiConnection) {
    private val _state = MutableStateFlow(SessionsState())
    val state: StateFlow<SessionsState> = _state.asStateFlow()

    suspend fun refresh(): PiSocket.Reply {
        _state.value = _state.value.copy(loading = true, error = null)
        val reply = connection.meta("sessions.list")
        if (!reply.ok) {
            _state.value = _state.value.copy(loading = false, error = reply.error ?: "Could not list sessions.")
            return reply
        }
        // The host groups by project; flatten and order newest-first here.
        val rows = SessionGrouping.parse(reply.data).sortedByDescending { it.mtimeMs }
        _state.value = SessionsState(rows = rows, groups = SessionGrouping.group(rows), loading = false)
        return reply
    }

    suspend fun rename(row: SessionRow, name: String): PiSocket.Reply {
        if (row.path.isBlank()) return PiSocket.Reply(ok = false, error = "That session has no path.")
        val reply = connection.meta(
            "sessions.rename",
            buildJsonObject {
                put("path", row.path)
                put("name", name)
            },
        )
        if (reply.ok) refresh()
        return reply
    }

    suspend fun delete(row: SessionRow): PiSocket.Reply {
        if (row.path.isBlank()) return PiSocket.Reply(ok = false, error = "That session has no path.")
        val reply = connection.meta("sessions.delete", buildJsonObject { put("path", row.path) })
        if (reply.ok) refresh()
        return reply
    }

    fun clear() {
        _state.value = SessionsState()
    }
}
