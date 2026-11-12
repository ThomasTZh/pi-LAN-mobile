package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {
    private fun row(
        path: String,
        cwd: String? = null,
        mtimeMs: Long = 0L,
        name: String? = null,
        firstPrompt: String? = null,
        messageCount: Int = 0,
        cwdExists: Boolean = true,
    ) = SessionRow(
        path = path,
        mtimeMs = mtimeMs,
        size = 0,
        sessionId = null,
        name = name,
        firstPrompt = firstPrompt,
        messageCount = messageCount,
        cwd = cwd,
        cwdExists = cwdExists,
    )

    @Test
    fun parsesSessionsListPayload() {
        val data = Json.parseToJsonElement(
            """
            {"sessions":[
              {"path":"/p/a.jsonl","mtimeMs":100,"size":20,"sessionId":"s1","name":"Named","firstPrompt":"hi","messageCount":3,"cwd":"/p","cwdExists":true},
              {"path":"/p/b.jsonl"},
              {"nope":true}
            ]}
            """.trimIndent(),
        )
        val rows = SessionGrouping.parse(data)
        assertEquals(2, rows.size)
        assertEquals("Named", rows[0].name)
        assertEquals(100L, rows[0].mtimeMs)
        assertEquals(20L, rows[0].size)
        assertEquals("s1", rows[0].sessionId)
        assertEquals(3, rows[0].messageCount)
        assertTrue(rows[0].cwdExists)
        assertEquals("/p/b.jsonl", rows[1].path)
        assertEquals(0L, rows[1].mtimeMs)
    }

    @Test
    fun flattensTheGroupedSessionsListPayload() {
        val data = Json.parseToJsonElement(
            """
            {"projects":[
              {"project":"/p/a","sessions":[{"path":"/p/a/1.jsonl","mtimeMs":5,"cwd":"/p/a"}]},
              {"project":"/p/b","sessions":[{"path":"/p/b/1.jsonl","mtimeMs":9,"cwd":"/p/b"},{"path":"/p/b/2.jsonl","mtimeMs":7,"cwd":"/p/b"}]}
            ]}
            """.trimIndent(),
        )
        val rows = SessionGrouping.parse(data)
        assertEquals(listOf("/p/a/1.jsonl", "/p/b/1.jsonl", "/p/b/2.jsonl"), rows.map { it.path })
        val groups = SessionGrouping.group(rows)
        assertEquals(listOf("b", "a"), groups.map { it.project })
    }

    @Test
    fun groupsByProjectNewestFirst() {
        val rows = listOf(
            row("/p/a/one.jsonl", cwd = "/p/a", mtimeMs = 100),
            row("/p/b/one.jsonl", cwd = "/p/b", mtimeMs = 200),
            row("/p/a/two.jsonl", cwd = "/p/a", mtimeMs = 300),
        )
        val groups = SessionGrouping.group(rows)
        assertEquals(2, groups.size)
        assertEquals("a", groups[0].project)
        assertEquals("/p/a", groups[0].cwd)
        assertEquals(listOf("/p/a/two.jsonl", "/p/a/one.jsonl"), groups[0].rows.map { it.path })
        assertEquals(300L, groups[0].latestMtimeMs)
        assertEquals("b", groups[1].project)
    }

    @Test
    fun rowsWithoutCwdShareOneGroup() {
        val rows = listOf(
            row("/x/one.jsonl", cwd = null, mtimeMs = 10),
            row("/x/two.jsonl", cwd = "", mtimeMs = 20),
            row("/p/three.jsonl", cwd = "/p", mtimeMs = 5),
        )
        val groups = SessionGrouping.group(rows)
        assertEquals(2, groups.size)
        assertEquals("Sessions", groups[0].project)
        assertEquals(2, groups[0].rows.size)
        assertEquals("p", groups[1].project)
        assertEquals(emptyList<SessionGroup>(), SessionGrouping.group(emptyList()))
    }

    @Test
    fun displayNameFallsBackToFirstPromptLine() {
        assertEquals("Named", row("/a.jsonl", name = "Named", firstPrompt = "prompt").displayName)
        assertEquals("Fix the build", row("/a.jsonl", firstPrompt = "Fix the build\nwith more detail").displayName)
        assertEquals("Untitled session", row("/a.jsonl").displayName)
        assertEquals("project", row("/a.jsonl", cwd = "/home/pi/project/").project)
        assertEquals("Sessions", row("/a.jsonl").project)
    }
}
