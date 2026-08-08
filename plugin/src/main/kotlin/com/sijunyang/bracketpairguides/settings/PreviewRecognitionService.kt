package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns preview jobs under the application and plugin lifecycle. */
@Service(Service.Level.APP)
internal class PreviewRecognitionService(
    private val coroutineScope: CoroutineScope,
) {
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = coroutineScope.launch(
        context = ModalityState.any().asContextElement() +
            CoroutineName("Bracket settings preview recognition"),
        block = block,
    )

    companion object {
        fun getInstance(): PreviewRecognitionService = service()
    }
}
