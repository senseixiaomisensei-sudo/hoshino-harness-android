package app.hoshino.harness.testing

import app.hoshino.harness.core.ModelInfo
import app.hoshino.harness.core.ModelRef
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.Provider
import app.hoshino.harness.core.ProviderProtocol
import app.hoshino.harness.core.ReasoningEffort
import app.hoshino.harness.core.TurnOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacyConstructorsTest {
    /** Stands in for "the current source added defaulted parameters after the ones an old APK knows". */
    data class Grown(val id: String, val count: Int, val flag: Boolean, val extra: String = "dflt", val more: Long = 7L)
    data class Wide(
        val a0: Int, val a1: Int = 1, val a2: Int = 2, val a3: Int = 3, val a4: Int = 4, val a5: Int = 5, val a6: Int = 6, val a7: Int = 7,
        val a8: Int = 8, val a9: Int = 9, val a10: Int = 10, val a11: Int = 11, val a12: Int = 12, val a13: Int = 13, val a14: Int = 14, val a15: Int = 15,
        val a16: Int = 16, val a17: Int = 17, val a18: Int = 18, val a19: Int = 19, val a20: Int = 20, val a21: Int = 21, val a22: Int = 22, val a23: Int = 23,
        val a24: Int = 24, val a25: Int = 25, val a26: Int = 26, val a27: Int = 27, val a28: Int = 28, val a29: Int = 29, val a30: Int = 30, val a31: Int = 31,
        val a32: Int = 32, val a33: String = "33",
    )

    @Test fun leadingArgsFillTrailingKotlinDefaults() {
        val v = LegacyConstructors.new<Grown>("x", 3, true)
        assertEquals(Grown("x", 3, true), v)
        assertEquals("dflt", v.extra); assertEquals(7L, v.more)
    }

    @Test fun exactConstructorIsPreferredWhenAllArgsGiven() {
        assertEquals(Grown("x", 3, true, "e", 9L), LegacyConstructors.new<Grown>("x", 3, true, "e", 9L))
    }

    @Test fun moreThan32ParametersUseTwoMasks() {
        val v = LegacyConstructors.new<Wide>(100, 101)
        assertEquals(Wide(100, 101), v)
        assertEquals(32, v.a32); assertEquals("33", v.a33)
    }

    @Test fun incompatibleArgsFailLoudly() {
        try { LegacyConstructors.new<Grown>(1, "x"); fail("expected failure") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Grown"))
        }
    }

    /** The exact leading argument lists the upgrade-seed test uses on the shipped data classes. */
    @Test fun appDataClassesBuildFromTheirLegacyArgumentLists() {
        val model = LegacyConstructors.new<ModelInfo>("mock-model", "M21 Mock", 128000, 4096, true, true, ReasoningEffort.Off)
        assertEquals("mock-model", model.id); assertEquals(128000, model.contextLength)
        assertEquals(ModelInfo("mock-model", "M21 Mock", 128000, 4096, true, true, ReasoningEffort.Off), model)
        val provider = LegacyConstructors.new<Provider>("m21-mock", "M21 preserved mock", "custom", ProviderProtocol.OpenAICompatible,
            "http://127.0.0.1:8099/v1/chat/completions", "k", emptyList<Pair<String, String>>(), true, listOf(model))
        assertEquals(listOf(model), provider.models)
        val ref = LegacyConstructors.new<ModelRef>("m21-mock", "mock-model")
        val turn = LegacyConstructors.new<TurnOptions>("standard", PermissionTier.WorkspaceWrite, ref, ReasoningEffort.Off, false)
        assertEquals(TurnOptions("standard", PermissionTier.WorkspaceWrite, ModelRef("m21-mock", "mock-model"), ReasoningEffort.Off, false), turn)
    }
}
