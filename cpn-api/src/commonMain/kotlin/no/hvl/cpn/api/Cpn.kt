package no.hvl.cpn.api

import no.hvl.cpn.core.CoreModule
import no.hvl.cpn.io.IoModule
import no.hvl.cpn.lang.LangModule
import no.hvl.cpn.sim.SimModule
import no.hvl.cpn.statespace.StateSpaceModule

/** Entry point of the public API. Grows into `Cpn.load(...)` in T3.7. */
public object Cpn {
    public const val VERSION: String = "0.1.0-SNAPSHOT"

    /** Name of the platform the engine is running on, e.g. `jvm`, `js`, `native-macosArm64`. */
    public val platform: String get() = platformName()

    /** Engine modules linked into this build. */
    public val modules: List<String> =
        listOf(CoreModule.NAME, LangModule.NAME, SimModule.NAME, StateSpaceModule.NAME, IoModule.NAME)
}

internal expect fun platformName(): String
