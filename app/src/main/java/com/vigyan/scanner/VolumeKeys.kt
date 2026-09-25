package com.vigyan.scanner

/** While Quick scan is open, the volume keys take a picture (MainActivity forwards them here). */
object VolumeKeys {
    @Volatile
    var handler: (() -> Unit)? = null
}
