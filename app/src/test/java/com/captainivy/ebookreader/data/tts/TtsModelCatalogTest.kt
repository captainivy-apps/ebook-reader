package com.captainivy.ebookreader.data.tts

import com.captainivy.ebookreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsModelCatalogTest {

    @Test
    fun idsAreUnique() {
        val ids = TtsModelCatalog.models.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun everyModelHasRequiredFiles() {
        TtsModelCatalog.models.forEach { spec ->
            assertTrue("$spec has no files", spec.files.isNotEmpty())
            val localNames = spec.localFiles()
            assertTrue("$spec model missing", spec.modelFile in localNames)
            assertTrue("$spec tokens missing", spec.tokensFile in localNames)
            assertTrue("$spec lexicon missing", spec.lexiconFiles.all { it in localNames })
            assertTrue("$spec rules missing", spec.ruleFsts.all { it in localNames })
            spec.voicesFile?.let {
                assertTrue("$spec voices missing", it in localNames)
            }
            assertTrue("$spec has no size", spec.approxSizeMb > 0)
        }
    }

    @Test
    fun catalogContainsChineseAndBilingualModels() {
        val languages = TtsModelCatalog.models.map { it.language }
        assertTrue(languages.any { it == "中文" })
        assertTrue(languages.any { it == "英文" })
        assertTrue(languages.any { it.contains("中文") && it.contains("英文") })
    }

    @Test
    fun buildsOriginalAndMirrorUrls() {
        val spec = TtsModelCatalog.models.first()
        val original = TtsModelCatalog.urls(spec, Prefs.TTS_SOURCE_ORIGINAL)
        val mirror = TtsModelCatalog.urls(spec, Prefs.TTS_SOURCE_MIRROR)
        assertEquals(1, original.size)
        assertTrue(original.first().startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
        assertTrue(original.first().endsWith(spec.archiveName))
        assertTrue(mirror.isNotEmpty())
        assertTrue(mirror.all { it.endsWith(spec.archiveName) })
        assertTrue(mirror.all { "github.com/k2-fsa/sherpa-onnx" in it })
    }

    @Test
    fun lookupById() {
        TtsModelCatalog.models.forEach {
            assertNotNull(TtsModelCatalog.byId(it.id))
        }
        assertEquals(null, TtsModelCatalog.byId("missing"))
    }
}
