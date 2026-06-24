// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

// Top-level build file. Plugins are declared `apply false` so each
// sub-module that actually uses them brings them in.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
