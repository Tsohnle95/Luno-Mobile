package com.luno.mobile.ui.shell

import com.luno.mobile.data.repository.LibraryRepository
import com.luno.mobile.data.repository.MetadataRepairResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MetadataRepairStatus {
    data class Progress(val scanned: Int, val total: Int, val updated: Int) : MetadataRepairStatus
    data class Finished(val result: MetadataRepairResult) : MetadataRepairStatus
    data class Failed(val message: String) : MetadataRepairStatus
}

/** Owns repair across navigation; running it twice cannot overlap writes. */
class MetadataRepairManager(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository
) {
    private val mutableStatus = MutableStateFlow<MetadataRepairStatus?>(null)
    val status = mutableStatus.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        mutableStatus.value = MetadataRepairStatus.Progress(0, 0, 0)
        job = scope.launch {
            try {
                val result = repository.repairMissingMetadata { scanned, total, updated ->
                    mutableStatus.value = MetadataRepairStatus.Progress(scanned, total, updated)
                }
                mutableStatus.value = MetadataRepairStatus.Finished(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableStatus.value = MetadataRepairStatus.Failed(error.message ?: "Could not repair song details")
            }
        }
    }

    fun dismiss() {
        if (mutableStatus.value !is MetadataRepairStatus.Progress) mutableStatus.value = null
    }
}
