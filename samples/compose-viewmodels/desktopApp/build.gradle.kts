// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { application }

application { mainClass = "dev.zacsweers.metro.sample.composeviewmodels.app.MainKt" }

dependencies { implementation(project(":compose-viewmodels:app")) }
