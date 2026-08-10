package com.example.limbuszhcn.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class MicrogContainerConfigTest {
    @Test
    fun bundledManifestPinsRequiredPackagesAndMatchingAssets() {
        val assetRoot = listOf(
            File("src/main/assets/microg"),
            File("app/src/main/assets/microg")
        ).first(File::isDirectory)
        val manifest = assetRoot.resolve("manifest.properties").inputStream()
            .use(::parseBundledMicrogManifest)

        assertEquals(
            setOf("com.google.android.gms", "com.android.vending"),
            manifest.entries.map(BundledMicrogEntry::packageName).toSet()
        )
        manifest.entries.forEach { entry ->
            entry.assetParts.forEach { partName ->
                assertTrue("Missing $partName", assetRoot.resolve(partName).isFile)
            }
            assertEquals(entry.sha256, entry.assetParts.sha256(assetRoot))
        }
    }

    @Test
    fun rejectsTraversalAndMalformedDigests() {
        val properties = """
            bundleVersion=1
            entries=../gms.apk
            ../gms.apk.packageName=com.google.android.gms
            ../gms.apk.versionCode=1
            ../gms.apk.sha256=bad
            ../gms.apk.certificateSha256=bad
        """.trimIndent()

        val result = runCatching {
            parseBundledMicrogManifest(ByteArrayInputStream(properties.toByteArray()))
        }

        assertTrue(result.isFailure)
    }

    private fun List<String>.sha256(assetRoot: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        forEach { partName ->
            assetRoot.resolve(partName).inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
