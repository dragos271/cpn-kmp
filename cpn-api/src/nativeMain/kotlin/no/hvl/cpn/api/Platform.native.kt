package no.hvl.cpn.api

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
internal actual fun platformName(): String = "native-${Platform.osFamily.name.lowercase()}-${Platform.cpuArchitecture.name.lowercase()}"
