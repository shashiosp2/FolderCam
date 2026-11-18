package com.example.service

import com.example.data.MediaSaveResult
import com.example.data.MediaStorageManager
import com.example.model.MediaFolder
import com.example.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Streaming producer-consumer RAM buffer for high-speed up-to-100-shot photo bursts.
 *
 * Keeps compressed JPEG ByteArrays in a heap-budgeted RAM queue (capped at 40% of
 * Runtime.maxMemory()) while 3 concurrent IO workers continuously drain frames to /sdcard.
 * If ultra-fast bursts outpace storage writes and reach the heap cap, excess frames
 * automatically spill to fast app-private cache files instead of risking OutOfMemoryError.
 */
class BurstBufferManager(
    private val mediaStorageManager: MediaStorageManager,
    private val scope: CoroutineScope,
    private val onFrameDrained: (MediaFolder, MediaSaveResult) -> Unit,
    private val onFrameFailed: (String) -> Unit
) {
    private sealed interface BufferedFrame {
        val folder: MediaFolder
        val fileName: String

        class InMemory(
            override val folder: MediaFolder,
            override val fileName: String,
            val bytes: ByteArray
        ) : BufferedFrame

        class SpillFile(
            override val folder: MediaFolder,
            override val fileName: String,
            val file: File
        ) : BufferedFrame
    }

    private val queue = Channel<BufferedFrame>(capacity = Channel.UNLIMITED)
    private val ramBytes = AtomicLong(0L)
    private val pendingCount = AtomicInteger(0)
    private val ramFrameCount = AtomicInteger(0)

    private val calculatedRamBudgetBytes: Long by lazy {
        val maxHeap = Runtime.getRuntime().maxMemory().takeIf { it > 0L && it != Long.MAX_VALUE }
            ?: (256L * 1024L * 1024L)
        (maxHeap * 0.40).toLong().coerceAtLeast(32L * 1024L * 1024L)
    }

    init {
        repeat(3) {
            scope.launch(Dispatchers.IO) {
                for (frame in queue) {
                    processFrame(frame)
                }
            }
        }
    }

    fun getPendingCount(): Int = pendingCount.get()
    fun getRamFrameCount(): Int = ramFrameCount.get()
    fun getRamBufferedBytes(): Long = ramBytes.get()
    fun getRamBudgetBytes(): Long = calculatedRamBudgetBytes

    fun enqueueCapturedBytes(folder: MediaFolder, bytes: ByteArray): Boolean {
        val fileName = mediaStorageManager.generateUniqueFileName(MediaType.PHOTO)
        pendingCount.incrementAndGet()

        val currentRam = ramBytes.get()
        val canFitInRam = currentRam + bytes.size <= calculatedRamBudgetBytes
        val frame: BufferedFrame = if (canFitInRam) {
            ramBytes.addAndGet(bytes.size.toLong())
            ramFrameCount.incrementAndGet()
            BufferedFrame.InMemory(folder, fileName, bytes)
        } else {
            try {
                val spill = mediaStorageManager.prepareBurstSpillFile(fileName)
                spill.writeBytes(bytes)
                BufferedFrame.SpillFile(folder, fileName, spill)
            } catch (e: Exception) {
                // Fallback to RAM if cache write throws
                ramBytes.addAndGet(bytes.size.toLong())
                ramFrameCount.incrementAndGet()
                BufferedFrame.InMemory(folder, fileName, bytes)
            }
        }

        val sent = queue.trySend(frame)
        if (!sent.isSuccess) {
            if (frame is BufferedFrame.InMemory) {
                ramBytes.addAndGet(-frame.bytes.size.toLong())
                ramFrameCount.decrementAndGet()
            } else if (frame is BufferedFrame.SpillFile) {
                frame.file.delete()
            }
            pendingCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            onFrameFailed("Burst queue closed")
            return false
        }
        return true
    }

    private suspend fun processFrame(frame: BufferedFrame) {
        try {
            val result = when (frame) {
                is BufferedFrame.InMemory -> {
                    ramBytes.updateAndGet { cur -> (cur - frame.bytes.size.toLong()).coerceAtLeast(0L) }
                    ramFrameCount.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
                    mediaStorageManager.saveBytesToFolder(
                        folder = frame.folder,
                        fileName = frame.fileName,
                        bytes = frame.bytes,
                        type = MediaType.PHOTO
                    )
                }
                is BufferedFrame.SpillFile -> {
                    val bytes = try {
                        frame.file.readBytes()
                    } finally {
                        frame.file.delete()
                    }
                    mediaStorageManager.saveBytesToFolder(
                        folder = frame.folder,
                        fileName = frame.fileName,
                        bytes = bytes,
                        type = MediaType.PHOTO
                    )
                }
            }
            pendingCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            onFrameDrained(frame.folder, result)
        } catch (e: Exception) {
            pendingCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            onFrameFailed(e.message ?: "Failed to save burst frame")
        }
    }
}
