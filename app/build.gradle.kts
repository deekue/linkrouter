plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing is driven entirely by external secrets (never hardcoded):
// RELEASE_KEYSTORE, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD,
// RELEASE_STORE_PASSWORD — each read from a `gradle.properties` key or an
// environment variable of the same name (see gradle.properties.example).
// If any is missing the release build type is simply left unsigned so
// local/CI builds keep working.
private fun releaseSecret(name: String): String? =
    project.findProperty(name)?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "net.chaosengine.linkrouter"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "net.chaosengine.linkrouter"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("VERSION_CODE") as? String)?.toIntOrNull() ?: 201
        versionName = (project.findProperty("VERSION_NAME") as? String) ?: "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Applied only when ALL RELEASE_* secrets are present (helper above);
        // otherwise this config is never used and the build stays unsigned.
        create("release") {
            val keystore = releaseSecret("RELEASE_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = releaseSecret("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSecret("RELEASE_KEY_ALIAS")
                keyPassword = releaseSecret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8/minify on for release only; debug stays unminified.
            isMinifyEnabled = true
            // Keep-rules for Moshi/Room can be added here if R8 needs them.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val hasSigning = listOf("RELEASE_KEYSTORE", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD", "RELEASE_STORE_PASSWORD")
                .all { releaseSecret(it) != null }
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        // Modern AGP (>= 8.0) does not generate BuildConfig by default; enable it
        // so DispatcherActivity can reference BuildConfig.DEBUG (DESIGN.md §11).
        buildConfig = true
    }
}

buildscript {
    dependencies {
        classpath("org.json:json:20240303")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Persistence (D3: Room)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Browser icons
    implementation("io.coil-kt:coil-compose:2.7.0")

    // JSON import/export (local only, no network)
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose UI tests
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// --- Build-time generation of BuiltInExamples from linkrouter-rules-examples.json ---
val generateBuiltIns = tasks.register("generateBuiltIns") {
    val jsonFile = layout.projectDirectory.file("linkrouter-rules-examples.json")
    val outDir = layout.buildDirectory.dir("generated/builtin-kt/src")
    val outFile = outDir.map { it.file("net/chaosengine/linkrouter/rules/BuiltInExamples.kt") }

    inputs.file(jsonFile)
    outputs.dir(outDir)

    doLast {
        val root = org.json.JSONObject(jsonFile.asFile.readText())
        val sb = StringBuilder()
        sb.appendLine("// Auto-generated from linkrouter-rules-examples.json. Do not edit.")
        sb.appendLine("// generated by the generateBuiltIns Gradle task")
        sb.appendLine("package net.chaosengine.linkrouter.rules")
        sb.appendLine()

        // --- redirectFormats ---
        sb.appendLine("val builtInRedirectFormats: List<RedirectFormat> = listOf(")
        val rf = root.getJSONArray("redirectFormats")
        for (i in 0 until rf.length()) {
            val o = rf.getJSONObject(i)
            sb.appendLine("    RedirectFormat(")
            sb.appendLine("        id = -10${i}L,")
            sb.appendLine("        name = \"${o.getString("name")}\",")
            sb.appendLine("        pattern = \"${o.getString("pattern")}\",")
            sb.appendLine("        matchType = MatchType.${o.getString("matchType")},")
            sb.appendLine("        extractType = ExtractType.${o.getString("extractType")},")
            sb.appendLine("        extractTarget = \"${o.getString("extractTarget")}\",")
            sb.appendLine("        enabled = ${o.getBoolean("enabled")},")
            sb.appendLine("        priority = ${o.getInt("priority")},")
            sb.appendLine("        isBuiltIn = true,")
            sb.appendLine("        openRealDestination = ${o.getBoolean("openRealDestination")},")
            sb.appendLine("    ),")
        }
        sb.appendLine(")")
        sb.appendLine()

        // --- builtInExamples: name -> example input/expectedOutput pairs ---
        // (read from each rule's optional "examples" array; emptyList() if absent)
        sb.appendLine("val builtInExamples: Map<String, List<RuleExample>> = mapOf(")
        for (i in 0 until rf.length()) {
            val o = rf.getJSONObject(i)
            sb.appendLine("    \"${o.getString("name")}\" to ")
            val ex = if (o.has("examples")) o.getJSONArray("examples") else org.json.JSONArray()
            if (ex.length() == 0) {
                sb.appendLine("        emptyList(),")
            } else {
                sb.appendLine("        listOf(")
                for (j in 0 until ex.length()) {
                    val e = ex.getJSONObject(j)
                    sb.appendLine("            RuleExample(")
                    sb.appendLine("                input = \"${e.getString("input")}\",")
                    sb.appendLine("                expectedOutput = \"${e.getString("expectedOutput")}\",")
                    sb.appendLine("            ),")
                }
                sb.appendLine("        ),")
            }
        }
        sb.appendLine(")")
        sb.appendLine()

        // --- queryParamFilters ---
        sb.appendLine("val builtInQueryParamFilters: List<QueryParamFilter> = listOf(")
        val qpf = root.getJSONArray("queryParamFilters")
        for (i in 0 until qpf.length()) {
            val o = qpf.getJSONObject(i)
            val host = if (o.isNull("host") || !o.has("host")) "null" else "\"${o.getString("host")}\""
            sb.appendLine("    QueryParamFilter(")
            sb.appendLine("        id = -20${i}L,")
            sb.appendLine("        name = \"${o.getString("param")}\",")
            sb.appendLine("        host = $host,")
            sb.appendLine("        param = \"${o.getString("param")}\",")
            sb.appendLine("        enabled = ${o.getBoolean("enabled")},")
            sb.appendLine("        priority = 1000,")
            sb.appendLine("        isBuiltIn = true,")
            sb.appendLine("    ),")
        }
        sb.appendLine(")")
        sb.appendLine()

        // --- shortenerHosts ---
        sb.appendLine("val builtInShortenerHosts: List<ShortenerHost> = listOf(")
        val sh = root.getJSONArray("shortenerHosts")
        for (i in 0 until sh.length()) {
            val o = sh.getJSONObject(i)
            val pathPrefix = if (o.isNull("pathPrefix") || !o.has("pathPrefix")) "null" else "\"${o.getString("pathPrefix")}\""
            sb.appendLine("    ShortenerHost(")
            sb.appendLine("        id = -30${i}L,")
            sb.appendLine("        name = \"${o.getString("name")}\",")
            sb.appendLine("        host = \"${o.getString("host")}\",")
            sb.appendLine("        pathPrefix = $pathPrefix,")
            sb.appendLine("        enabled = ${o.getBoolean("enabled")},")
            sb.appendLine("        priority = 1000,")
            sb.appendLine("        isBuiltIn = true,")
            sb.appendLine("    ),")
        }
        sb.appendLine(")")
        sb.appendLine()

        // --- hostRewrites ---
        sb.appendLine("val builtInHostRewrites: List<HostRewrite> = listOf(")
        val hr = root.getJSONArray("hostRewrites")
        for (i in 0 until hr.length()) {
            val o = hr.getJSONObject(i)
            sb.appendLine("    HostRewrite(")
            sb.appendLine("        id = -40${i}L,")
            sb.appendLine("        matchHost = \"${o.getString("matchHost")}\",")
            sb.appendLine("        matchType = RewriteMatchType.${o.getString("matchType")},")
            sb.appendLine("        kind = RewriteKind.${o.getString("kind")},")
            sb.appendLine("        targetHost = \"${o.getString("targetHost")}\",")
            sb.appendLine("        preserveHostInPath = ${o.getBoolean("preserveHostInPath")},")
            sb.appendLine("        enabled = ${o.getBoolean("enabled")},")
            sb.appendLine("        priority = ${i + 1},")
            sb.appendLine("        isBuiltIn = true,")
            sb.appendLine("    ),")
        }
        sb.appendLine(")")
        sb.appendLine()

        // --- builtInParamFilterExamples: param -> example input/expectedOutput pairs ---
        sb.appendLine("val builtInParamFilterExamples: Map<String, List<RuleExample>> = mapOf(")
        for (i in 0 until qpf.length()) {
            val o = qpf.getJSONObject(i)
            sb.appendLine("    \"${o.getString("param")}\" to ")
            val ex = if (o.has("examples")) o.getJSONArray("examples") else org.json.JSONArray()
            if (ex.length() == 0) {
                sb.appendLine("        emptyList(),")
            } else {
                sb.appendLine("        listOf(")
                for (j in 0 until ex.length()) {
                    val e = ex.getJSONObject(j)
                    sb.appendLine("            RuleExample(")
                    sb.appendLine("                input = \"${e.getString("input")}\",")
                    sb.appendLine("                expectedOutput = \"${e.getString("expectedOutput")}\",")
                    sb.appendLine("            ),")
                }
                sb.appendLine("        ),")
            }
        }
        sb.appendLine(")")
        sb.appendLine()

        // --- builtInHostRewriteExamples: matchHost -> example input/expectedOutput pairs ---
        sb.appendLine("val builtInHostRewriteExamples: Map<String, List<RuleExample>> = mapOf(")
        for (i in 0 until hr.length()) {
            val o = hr.getJSONObject(i)
            sb.appendLine("    \"${o.getString("matchHost")}\" to ")
            val ex = if (o.has("examples")) o.getJSONArray("examples") else org.json.JSONArray()
            if (ex.length() == 0) {
                sb.appendLine("        emptyList(),")
            } else {
                sb.appendLine("        listOf(")
                for (j in 0 until ex.length()) {
                    val e = ex.getJSONObject(j)
                    sb.appendLine("            RuleExample(")
                    sb.appendLine("                input = \"${e.getString("input")}\",")
                    sb.appendLine("                expectedOutput = \"${e.getString("expectedOutput")}\",")
                    sb.appendLine("            ),")
                }
                sb.appendLine("        ),")
            }
        }
        sb.appendLine(")")
        sb.appendLine()

        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        println("generateBuiltIns: wrote ${out}")
    }
}

// Wire the generated source dir into the main source set and make compile depend on the task.
// Pass a concrete File, NOT a Provider<Directory>: modern AGP rejects Provider
// instances on the SourceSet API (it can't tell read-only/generated from
// static/read-write). The task dependency is wired separately via dependsOn below.
val builtInSrcDirFile = layout.buildDirectory.dir("generated/builtin-kt/src").get().asFile
android.sourceSets["main"].kotlin.srcDir(builtInSrcDirFile)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateBuiltIns)
}
// KSP also reads the main Kotlin source set (which now includes the generated
// dir), so every KSP variant (debug/release/unitTest/androidTest) must depend on
// generateBuiltIns too — otherwise Gradle's strict task-dependency validation fails.
tasks.configureEach {
    if (name.startsWith("ksp")) {
        dependsOn(generateBuiltIns)
    }
}
