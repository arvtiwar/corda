package net.corda.node.internal

import net.corda.core.contracts.*
import net.corda.core.cordapp.CordappProvider
import net.corda.core.node.NetworkParameters
import net.corda.core.node.ServicesForResolution
import net.corda.core.node.services.AttachmentStorage
import net.corda.core.node.services.NetworkParametersStorage
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.ContractUpgradeWireTransaction
import net.corda.core.transactions.NotaryChangeWireTransaction
import net.corda.core.transactions.WireTransaction

data class ServicesForResolutionImpl(
        override val identityService: IdentityService,
        override val attachments: AttachmentStorage,
        override val cordappProvider: CordappProvider,
        override val networkParametersStorage: NetworkParametersStorage,
        private val validatedTransactions: TransactionStorage
) : ServicesForResolution {
    override val networkParameters: NetworkParameters get() = networkParametersStorage.lookup(networkParametersStorage.currentHash) ?:
            throw IllegalArgumentException("No current parameters in network parameters storage")

    @Throws(TransactionResolutionException::class)
    override fun loadState(stateRef: StateRef): TransactionState<*> {
        val stx = validatedTransactions.getTransaction(stateRef.txhash) ?: throw TransactionResolutionException(stateRef.txhash)
        return stx.resolveBaseTransaction(this).outputs[stateRef.index]
    }

    @Throws(TransactionResolutionException::class)
    override fun loadStates(stateRefs: Set<StateRef>): Set<StateAndRef<ContractState>> {
        return stateRefs.groupBy { it.txhash }.flatMap {
            val stx = validatedTransactions.getTransaction(it.key) ?: throw TransactionResolutionException(it.key)
            val baseTx = stx.resolveBaseTransaction(this)
            it.value.map { StateAndRef(baseTx.outputs[it.index], it) }
        }.toSet()
    }

    override fun loadContractAttachment(stateRef: StateRef): Attachment? {
        val coreTransaction = validatedTransactions.getTransaction(stateRef.txhash)?.coreTransaction
                ?: throw TransactionResolutionException(stateRef.txhash)
        when (coreTransaction) {
            is WireTransaction -> {
                val transactionState = coreTransaction.outRef<ContractState>(stateRef.index).state
                for (attachmentId in coreTransaction.attachments) {
                    val attachment = attachments.openAttachment(attachmentId)
                    if (attachment is ContractAttachment && transactionState.contract == attachment.contract) {
                        return attachment
                    }
                }
                return null
            }
            is ContractUpgradeWireTransaction -> {
                return attachments.openAttachment(coreTransaction.upgradedContractAttachmentId)
            }
            is NotaryChangeWireTransaction -> {
                throw TransactionResolutionException(stateRef.txhash)
            }
            else -> throw UnsupportedOperationException("Attempting to resolve attachment ${stateRef.index} of a ${coreTransaction.javaClass} transaction. This is not supported.")
        }
    }
}
