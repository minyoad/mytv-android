import java.security.MessageDigest

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("verifyPlayerArtifacts") {
    group = "verification"
    description = "Verify the complete set and SHA-256 of bundled player binaries"
    val manifest = layout.projectDirectory.file("player-artifacts.sha256")
    val binaries = files(fileTree("tv/libs") { include("*.aar") },
        fileTree("ijkplayer-java/jniLibs") { include("**/*.so") })
    inputs.file(manifest)
    inputs.files(binaries)
    doLast {
        val expected = manifest.asFile.readLines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.split("  ", limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) { "Invalid player artifact manifest" }
            parts[1] to parts[0]
        }
        val actual = binaries.files.associateBy { it.relativeTo(rootDir).invariantSeparatorsPath }
        check(expected.keys == actual.keys) { "Player artifact list changed; review player-artifacts.sha256" }
        expected.forEach { (path, hash) ->
            val digest = MessageDigest.getInstance("SHA-256")
            actual.getValue(path).inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Player artifact checksum mismatch: $path" }
        }
    }
}
