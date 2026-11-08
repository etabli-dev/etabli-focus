// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppDatabaseTest {
    private val directory: Path = Files.createTempDirectory("focus-db")
    private val file: Path = directory.resolve("etabli_focus.db")

    @AfterTest fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun columns(table: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add("${rs.getString("name")}:${rs.getString("type")}:" +
                                "${if (rs.getInt("notnull") == 1) "NN" else "N"}:pk${rs.getInt("pk")}")
                        }
                    }
                }
            }
        }

    @Test fun schemaMatchesTheAndroidRoomVersionTwoEntities() {
        AppDatabase.open(file).close()
        assertEquals(
            listOf("id:TEXT:NN:pk1", "name:TEXT:NN:pk0", "colorHex:TEXT:NN:pk0",
                "defaultFocusMinutes:INTEGER:NN:pk0", "defaultBreakMinutes:INTEGER:NN:pk0", "order:INTEGER:NN:pk0"),
            columns("category"),
        )
        assertEquals(
            listOf("id:TEXT:NN:pk1", "categoryID:TEXT:NN:pk0", "startedAtEpoch:INTEGER:NN:pk0",
                "plannedEndEpoch:INTEGER:NN:pk0", "actualEndEpoch:INTEGER:N:pk0", "completed:INTEGER:NN:pk0",
                "kind:TEXT:NN:pk0", "note:TEXT:N:pk0", "activeMillis:INTEGER:N:pk0"),
            columns("session"),
        )
        assertEquals(
            listOf("weekday:INTEGER:NN:pk1", "categoryID:TEXT:NN:pk2", "targetBlocks:INTEGER:NN:pk0"),
            columns("template_entry"),
        )
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) }
            }
        }
    }

    @Test fun historySurvivesReopeningAndSessionWritesAreIdempotentById() = runBlocking {
        val row = SessionEntity("s1", "research", 1_000, 61_000, 61_000, true, "focus", activeMillis = 60_000)
        AppDatabase.open(file).use { db ->
            db.categoryDao().insertAll(CategoryEntity.defaults())
            db.sessionDao().insert(row)
            db.sessionDao().insert(row)
            db.sessionDao().insert(row.copy(id = "s2", completed = false, actualEndEpoch = 30_000, activeMillis = 29_000))
            db.sessionDao().insert(row.copy(id = "s3", kind = "break"))
            db.sessionDao().insert(row.copy(id = "s4", startedAtEpoch = 10, activeMillis = null, note = "historical"))
        }
        AppDatabase.open(file).use { db ->
            assertEquals(6, db.categoryDao().count())
            assertEquals((0..5).toList(), db.categoryDao().observeAll().first().map { it.order })
            assertEquals(4, db.sessionDao().observeAll().first().size)
            val completedFocus = db.sessionDao().observeCompletedFocusSince(1_000).first()
            assertEquals(listOf(row), completedFocus, "incomplete, break and earlier rows are excluded")
            val historical = db.sessionDao().observeCompletedFocusSince(0).first().single { it.id == "s4" }
            assertNull(historical.activeMillis)
            assertEquals("historical", historical.note)
        }
    }

    @Test fun templateUpsertReplacesTheWeekdayCategoryPairAndObserversSeeWrites() = runBlocking {
        AppDatabase.open(file).use { db ->
            val dao = db.templateDao()
            dao.upsert(TemplateEntryEntity(2, "research", 2))
            dao.upsert(TemplateEntryEntity(2, "research", 3))
            dao.upsert(TemplateEntryEntity(3, "research", 1))
            assertEquals(
                setOf(TemplateEntryEntity(2, "research", 3), TemplateEntryEntity(3, "research", 1)),
                dao.observeAll().first().toSet(),
            )
            dao.delete(3, "research")
            assertEquals(listOf(TemplateEntryEntity(2, "research", 3)), dao.observeAll().first())
        }
    }

    @Test fun categoryUpdateAndDeleteLeaveHistoryUntouched() = runBlocking {
        AppDatabase.open(file).use { db ->
            val category = CategoryEntity("c1", "Deep work", "28A745", 25, 5, 0)
            db.categoryDao().insert(category)
            db.categoryDao().update(category.copy(name = "Writing", defaultFocusMinutes = 45))
            assertEquals("Writing", db.categoryDao().observeAll().first().single().name)
            db.sessionDao().insert(SessionEntity("s1", "c1", 0, 1, 1, true, "focus"))
            db.categoryDao().delete("c1")
            assertEquals(0, db.categoryDao().count())
            assertEquals("c1", db.sessionDao().observeAll().first().single().categoryID)
        }
    }

    @Test fun aNewerSchemaIsRefusedRatherThanRewritten() {
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.executeUpdate("PRAGMA user_version = 3") }
        }
        assertFailsWith<IllegalStateException> { AppDatabase.open(file) }
    }
}
