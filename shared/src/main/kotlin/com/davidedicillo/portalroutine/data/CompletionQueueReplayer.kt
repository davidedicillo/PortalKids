package com.davidedicillo.portalroutine.data

class CompletionQueueReplayer {
    suspend fun replayInOrder(
        operations: List<CompletionMutation>,
        send: suspend (CompletionMutation) -> Unit,
    ): List<CompletionMutation> {
        val acknowledged = mutableListOf<CompletionMutation>()
        for (operation in operations.sortedWith(compareBy({ it.changedAt }, { it.operationId }))) {
            send(operation)
            acknowledged += operation
        }
        return acknowledged
    }
}
