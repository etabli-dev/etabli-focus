// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// Room counterparts to the iOS SwiftData @Model types in
// EtabliFocus/App/Models.swift. Same identity, same semantics.

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: String,           // UUID string — stable across the SwiftData ports
    val name: String,
    val colorHex: String,                 // "28A745" etc.
    val defaultFocusMinutes: Int,
    val defaultBreakMinutes: Int,
    val order: Int,
) {
    companion object {
        // Six built-in categories — same set, same colors, same default
        // durations as the iOS app. Seeded once if the table is empty.
        fun defaults(): List<CategoryEntity> = listOf(
            CategoryEntity(UUID.randomUUID().toString(), "Own paper · Data analysis",       "28A745", 50, 10, 0),
            CategoryEntity(UUID.randomUUID().toString(), "Own paper · Manuscript writing",  "1F6FEB", 50, 10, 1),
            CategoryEntity(UUID.randomUUID().toString(), "External review",                  "C9A35B", 50, 10, 2),
            CategoryEntity(UUID.randomUUID().toString(), "Internal review",                  "8957E5", 50, 10, 3),
            CategoryEntity(UUID.randomUUID().toString(), "Literature research & reading",   "1F9DA5", 50, 10, 4),
            CategoryEntity(UUID.randomUUID().toString(), "Organizational / admin",           "6B6B6B", 25,  5, 5),
        )
    }
}

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id: String,
    val categoryID: String,               // not a foreign key — survives category deletion
    val startedAtEpoch: Long,
    val plannedEndEpoch: Long,
    val actualEndEpoch: Long?,            // nil while running / on cancel
    val completed: Boolean,
    val kind: String,                     // "focus" | "break"
    val note: String? = null,
)

@Entity(tableName = "template_entry", primaryKeys = ["weekday", "categoryID"])
data class TemplateEntryEntity(
    val weekday: Int,                     // 1=Sun..7=Sat (Calendar convention; matches iOS)
    val categoryID: String,
    val targetBlocks: Int,
)
