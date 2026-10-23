package dev.kotlinds.libretrorollback.libretro

import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrokmp.MemoryType
import dev.kotlinds.libretrorollback.stateHash
import dev.kotlinds.libretrorollback.CoreFingerprint
import dev.kotlinds.libretrorollback.RollbackCore
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * [RollbackCore] over a libretro core.
 *
 * Construct it after the game is loaded and the core has run at least one frame.
 *
 * Buffer serialization is discovered by reflection: a libretro-kmp build with
 * `serializeSize()` / `saveStateInto(ByteArray)` / `loadState(ByteArray, Int)` writes snapshots
 * straight into the engine's reusable ring buffers, with no per-frame `ByteArray` allocation. A
 * stock 0.1.2 build does not have those methods, so the adapter falls back to
 * `saveState()` / `loadState()`, which allocate once per call. The reflection bridge disappears
 * once buffer serialization is part of libretro-kmp's common API.
 */
class LibretroRollbackCore private constructor(
    private val core: LibretroCore,
    private val frontend: RollbackFrontend,
    override val stateSize: Int,
    override val fingerprint: CoreFingerprint,
    /** False forces the delta ring to scan, even when the core exports dirty pages (benchmarking). */
    private val enableDirtyPages: Boolean = true,
) : RollbackCore {

    private val saveStateInto: Method? = method("saveStateInto", ByteArray::class.java)
    private val loadStateBuffer: Method? = method(
        "loadState",
        ByteArray::class.java,
        Int::class.javaPrimitiveType!!,
    )
    private val readMemoryInto: Method? = method(
        "readMemoryInto",
        MemoryType::class.java,
        ByteArray::class.java,
    )
    private val dirtyPagesMethod: Method? = method("dirtyPages", ByteArray::class.java)

    /** Reused by [stateChecksum] so the per-frame checksum does not allocate. */
    private var memoryBuffer = ByteArray(0)

    private fun method(name: String, vararg parameters: Class<*>): Method? =
        core.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == parameters.size &&
                candidate.parameterTypes.contentEquals(parameters)
        }

    private fun invoke(method: Method, vararg arguments: Any?): Any? = try {
        method.invoke(core, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    override fun saveState(into: ByteArray): Int {
        if (saveStateInto != null) {
            // retro_serialize writes the core's state but is allowed to leave the rest of the
            // buffer undefined. The buffer is reused across frames, so clear it first: otherwise
            // stale bytes from an older frame enter the checksum and produce false desyncs.
            into.fill(0)
            val length = invoke(saveStateInto, into) as? Int ?: -1
            check(length > 0) { "${fingerprint.coreName} refused to save a state" }
            return length
        }
        val state = core.saveState() ?: error("${fingerprint.coreName} refused to save a state")
        check(state.size <= into.size) {
            "state grew to ${state.size} bytes, ring slot is ${into.size}"
        }
        state.copyInto(into)
        return state.size
    }

    override fun loadState(from: ByteArray, size: Int) {
        if (loadStateBuffer != null) {
            check(invoke(loadStateBuffer, from, size) as? Boolean == true) {
                "${fingerprint.coreName} refused the state"
            }
            return
        }
        val state = if (size == from.size) from else from.copyOf(size)
        check(core.loadState(state)) { "${fingerprint.coreName} refused the state" }
    }

    override fun runFrame(inputs: ByteArray, render: Boolean) {
        frontend.inputs = inputs
        frontend.render = render
        core.run()
    }

    override fun stateChecksum(): Long? {
        if (readMemoryInto != null) {
            if (memoryBuffer.isEmpty()) {
                val size = core.memorySize(MemoryType.SYSTEM_RAM).toInt()
                if (size <= 0) return null
                memoryBuffer = ByteArray(size)
            }
            val length = invoke(readMemoryInto, MemoryType.SYSTEM_RAM, memoryBuffer) as? Int ?: 0
            if (length > 0) return stateHash(memoryBuffer, 0, length)
        }
        return core.readMemory(MemoryType.SYSTEM_RAM)?.let { stateHash(it, 0, it.size) }
    }

    override fun dirtyPages(out: ByteArray): Int {
        if (!enableDirtyPages) return 0
        val method = dirtyPagesMethod ?: return 0
        return invoke(method, out) as? Int ?: 0
    }

    companion object {
        /** @param contentSha256 hash of the ROM, so both peers can refuse different content. */
        fun create(
            core: LibretroCore,
            frontend: RollbackFrontend,
            contentSha256: String = "",
            enableDirtyPages: Boolean = true,
        ): LibretroRollbackCore {
            val info = core.systemInfo
            // One allocation at startup is fine; per-frame saves use the buffer path when available.
            val stateSize = core.saveState()?.size
                ?: error("${info.libraryName} does not support save states")
            return LibretroRollbackCore(
                core = core,
                frontend = frontend,
                stateSize = stateSize,
                fingerprint = CoreFingerprint(
                    coreName = info.libraryName,
                    coreVersion = info.libraryVersion,
                    contentSha256 = contentSha256,
                    optionsHash = frontend.optionsHash,
                ),
                enableDirtyPages = enableDirtyPages,
            )
        }
    }
}

/** A loaded core, its frontend and its rollback adapter. */
class OpenedLibretroCore(
    val core: LibretroCore,
    val frontend: RollbackFrontend,
    val rollback: LibretroRollbackCore,
) : AutoCloseable {
    override fun close() = core.close()
}
