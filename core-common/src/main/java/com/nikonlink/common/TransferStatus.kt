// core-common/src/main/java/com/nikonlink/common/TransferStatus.kt
package com.nikonlink.common

enum class TransferStatus {
    Pending,
    Transferring,
    Completed,
    Failed;

    val isTerminal: Boolean get() = this == Completed || this == Failed
}
