// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.data

import java.util.UUID

// Desktop counterparts to the Room entities in android/.../data/Entities.kt
// (and the SwiftData models in ios/App/Models.swift). Same fields, same
// identity and the same table layout, without the Room annotations.

data class CategoryEntity(
    val id: String,                       // UUID string, stable across platforms
    val name: String,
    val colorHex: String,                 // "28A745" etc.
    val defaultFocusMinutes: Int,
    val defaultBreakMinutes: Int,
    val order: Int,
) {
    companion object {
        // Six built-in categories, identical to Android and iOS. Seeded once
        // if the table is empty.
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

data class SessionEntity(
    val id: String,
    val categoryID: String,               // not a foreign key: survives category deletion
    val startedAtEpoch: Long,
    val plannedEndEpoch: Long,
    val actualEndEpoch: Long?,            // null while running / on cancel
    val completed: Boolean,
    val kind: String,                     // "focus" | "break"
    val note: String? = null,
    val activeMillis: Long? = null,       // null for historical rows; never rewrite their provenance
)

data class TemplateEntryEntity(
    val weekday: Int,                     // 1=Sun..7=Sat (Calendar convention; matches iOS and Android)
    val categoryID: String,
    val targetBlocks: Int,
)
