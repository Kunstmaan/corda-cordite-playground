package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

// *********
// * Flows *
// *********

class IOUFlow {

    @InitiatingFlow
    @StartableByRPC
    @StartableByService
    class Initiator(val iouValue: Int, val otherParty: Party) : FlowLogic<SignedTransaction>() {

        /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            val txBuilder = TransactionBuilder(notary = notary)
            val outputState = IOUState(iouValue, ourIdentity, otherParty)
            val outputContractAndState = StateAndContract(outputState, IOU_CONTRACT_ID)
            val cmd = Command(IOUContract.Create(), listOf(ourIdentity.owningKey, otherParty.owningKey))

            txBuilder.withItems(outputContractAndState, cmd)

            // verify
            txBuilder.verify(serviceHub)

            // sign the tx
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // create a session with the other party
            val otherPartySession = initiateFlow(otherParty)

            // obtaining the signature of the other party
            val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherPartySession), CollectSignaturesFlow.tracker()))

            // finalize the transaction
            return subFlow(FinalityFlow(fullySignedTx))
        }

    }

    @InitiatedBy(com.template.IOUFlow.Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val signedTransaction = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {

                // add additional checks to the contract
                // what if we don't want to work with a certain party ... or we don't want the value to be too high
                // we will only sign if the contract is valid and these additional checks ...
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an IOU transaction." using (output is IOUState)

                    val iou = output as IOUState
                    "The IOU's value can't be too high." using (iou.value <= 100)
                }

            }

            subFlow(signedTransaction)
        }

    }

}

// Serialization whitelist.
class TemplateSerializationWhitelist : SerializationWhitelist {

    override val whitelist: List<Class<*>> = listOf(TemplateData::class.java)

}

// This class is not annotated with @CordaSerializable, so it must be added to the serialization whitelist, above, if
// we want to send it to other nodes within a flow.
data class TemplateData(val payload: String)
