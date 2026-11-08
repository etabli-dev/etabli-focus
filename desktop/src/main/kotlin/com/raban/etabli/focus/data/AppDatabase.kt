// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

// DAO contracts with the same names and signatures as the Room DAOs in
// android/.../data/Dao.kt, so screens read the same on both platforms and the
// implementation can later be swapped for Room KMP.

interface CategoryDao {
    fun observeAll(): Flow<List<CategoryEntity>>
    suspend fun count(): Int
    suspend fun insert(category: CategoryEntity)
    suspend fun insertAll(categories: List<CategoryEntity>)
    suspend fun update(category: CategoryEntity)
    suspend fun delete(id: String)
}

interface SessionDao {
    fun observeAll(): Flow<List<SessionEntity>>
    fun observeCompletedFocusSince(sinceEpoch: Long): Flow<List<SessionEntity>>
    suspend fun insert(session: SessionEntity)
}

interface TemplateDao {
    fun observeAll(): Flow<List<TemplateEntryEntity>>
    suspend fun upsert(entry: TemplateEntryEntity)
    suspend fun delete(weekday: Int, categoryID: String)
}

/**
 * Local history in one SQLite file (sqlite-jdbc). The tables, column types,
 * nullability and primary keys are exactly those Room generates for the
 * Android `AppDatabase` at schema version 2, and `user_version` carries the
 * same number.
 */
class AppDatabase private constructor(private val connection: Connection) : AutoCloseable {

    private val lock = Any()
    private val invalidation = MutableStateFlow(0L)

    private val categories = object : CategoryDao {
        override fun observeAll() = observe {
            query("SELECT * FROM category ORDER BY `order`") { it.toCategory() }
        }
        override suspend fun count() = read {
            query("SELECT COUNT(*) FROM category") { it.getInt(1) }.single()
        }
        override suspend fun insert(category: CategoryEntity) = write { putCategory(category) }
        override suspend fun insertAll(categories: List<CategoryEntity>) = write {
            categories.forEach { putCategory(it) }
        }
        override suspend fun update(category: CategoryEntity) = write {
            execute(
                "UPDATE category SET name = ?, colorHex = ?, defaultFocusMinutes = ?, " +
                    "defaultBreakMinutes = ?, `order` = ? WHERE id = ?",
                category.name, category.colorHex, category.defaultFocusMinutes,
                category.defaultBreakMinutes, category.order, category.id,
            )
        }
        override suspend fun delete(id: String) = write { execute("DELETE FROM category WHERE id = ?", id) }
    }

    private val sessions = object : SessionDao {
        override fun observeAll() = observe {
            query("SELECT * FROM session ORDER BY startedAtEpoch DESC") { it.toSession() }
        }
        override fun observeCompletedFocusSince(sinceEpoch: Long) = observe {
            query(
                "SELECT * FROM session WHERE completed = 1 AND kind = 'focus' AND startedAtEpoch >= ?",
                sinceEpoch,
            ) { it.toSession() }
        }
        override suspend fun insert(session: SessionEntity) = write {
            execute(
                "INSERT OR REPLACE INTO session (id, categoryID, startedAtEpoch, plannedEndEpoch, " +
                    "actualEndEpoch, completed, kind, note, activeMillis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                session.id, session.categoryID, session.startedAtEpoch, session.plannedEndEpoch,
                session.actualEndEpoch, session.completed, session.kind, session.note, session.activeMillis,
            )
        }
    }

    private val templates = object : TemplateDao {
        override fun observeAll() = observe {
            query("SELECT * FROM template_entry") {
                TemplateEntryEntity(it.getInt("weekday"), it.getString("categoryID"), it.getInt("targetBlocks"))
            }
        }
        override suspend fun upsert(entry: TemplateEntryEntity) = write {
            execute(
                "INSERT OR REPLACE INTO template_entry (weekday, categoryID, targetBlocks) VALUES (?, ?, ?)",
                entry.weekday, entry.categoryID, entry.targetBlocks,
            )
        }
        override suspend fun delete(weekday: Int, categoryID: String) = write {
            execute("DELETE FROM template_entry WHERE weekday = ? AND categoryID = ?", weekday, categoryID)
        }
    }

    fun categoryDao(): CategoryDao = categories
    fun sessionDao(): SessionDao = sessions
    fun templateDao(): TemplateDao = templates

    override fun close() = synchronized(lock) { connection.close() }

    // MARK: - Plumbing

    private fun <T> observe(block: () -> T): Flow<T> =
        invalidation.map { synchronized(lock) { block() } }.flowOn(Dispatchers.IO)

    private suspend fun <T> read(block: () -> T): T =
        withContext(Dispatchers.IO) { synchronized(lock) { block() } }

    private suspend fun write(block: () -> Unit) {
        withContext(Dispatchers.IO) {
            synchronized(lock) { transaction(block) }
            invalidation.update { it + 1 }
        }
    }

    private fun transaction(block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun putCategory(category: CategoryEntity) = execute(
        "INSERT OR REPLACE INTO category (id, name, colorHex, defaultFocusMinutes, defaultBreakMinutes, `order`) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
        category.id, category.name, category.colorHex, category.defaultFocusMinutes,
        category.defaultBreakMinutes, category.order,
    )

    private fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args)
            statement.executeUpdate()
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args)
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(row(rs)) }
            }
        }

    private fun PreparedStatement.bind(args: Array<out Any?>) {
        args.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is Boolean -> setInt(index + 1, if (value) 1 else 0)
                is Int -> setInt(index + 1, value)
                is Long -> setLong(index + 1, value)
                is String -> setString(index + 1, value)
                else -> error("Unsupported SQL argument type ${value::class}")
            }
        }
    }

    private fun ResultSet.toCategory() = CategoryEntity(
        id = getString("id"),
        name = getString("name"),
        colorHex = getString("colorHex"),
        defaultFocusMinutes = getInt("defaultFocusMinutes"),
        defaultBreakMinutes = getInt("defaultBreakMinutes"),
        order = getInt("order"),
    )

    private fun ResultSet.toSession() = SessionEntity(
        id = getString("id"),
        categoryID = getString("categoryID"),
        startedAtEpoch = getLong("startedAtEpoch"),
        plannedEndEpoch = getLong("plannedEndEpoch"),
        actualEndEpoch = nullableLong("actualEndEpoch"),
        completed = getInt("completed") != 0,
        kind = getString("kind"),
        note = getString("note"),
        activeMillis = nullableLong("activeMillis"),
    )

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    companion object {
        /** Room schema version of android/.../data/AppDatabase.kt. */
        const val SCHEMA_VERSION = 2

        /** The DDL Room emits for the version-2 entities, verbatim. */
        internal val CREATE_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `category` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`colorHex` TEXT NOT NULL, `defaultFocusMinutes` INTEGER NOT NULL, " +
                "`defaultBreakMinutes` INTEGER NOT NULL, `order` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `session` (`id` TEXT NOT NULL, `categoryID` TEXT NOT NULL, " +
                "`startedAtEpoch` INTEGER NOT NULL, `plannedEndEpoch` INTEGER NOT NULL, " +
                "`actualEndEpoch` INTEGER, `completed` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                "`note` TEXT, `activeMillis` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `template_entry` (`weekday` INTEGER NOT NULL, " +
                "`categoryID` TEXT NOT NULL, `targetBlocks` INTEGER NOT NULL, " +
                "PRIMARY KEY(`weekday`, `categoryID`))",
        )

        fun open(file: Path): AppDatabase {
            file.parent?.let { Files.createDirectories(it) }
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
            try {
                migrate(connection)
            } catch (failure: Throwable) {
                connection.close()
                throw failure
            }
            return AppDatabase(connection)
        }

        private fun migrate(connection: Connection) {
            val version = connection.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
            }
            check(version == 0 || version == SCHEMA_VERSION) {
                "History database has schema version $version; this build understands $SCHEMA_VERSION."
            }
            if (version == SCHEMA_VERSION) return
            connection.autoCommit = false
            try {
                connection.createStatement().use { st ->
                    CREATE_STATEMENTS.forEach { st.executeUpdate(it) }
                    st.executeUpdate("PRAGMA user_version = $SCHEMA_VERSION")
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }
}
