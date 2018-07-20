package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.seconds
import net.corda.core.utilities.unwrap
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCashBy
import java.security.PublicKey
import java.util.*

object TwoPartyTradeFlow {

    class UnacceptablePriceException(givenPrice: Amount<Currency>) : FlowException("Unacceptable price: $givenPrice")

    class AssetMismatchException(val expectedTypeName: String, val typeName: String) : FlowException() {

        override fun toString(): String = "The submitted asset didn't match the expected type: $expectedTypeName vs $typeName"

    }

    @CordaSerializable
    data class SellerTradeInfo(
            val price: Amount<Currency>,
            val payToIdentity: PartyAndCertificate
    )

    open class Seller(
            private val otherSideSession: FlowSession,
            private val assetToSell: StateAndRef<OwnableState>,
            private val price: Amount<Currency>,
            private val myParty: PartyAndCertificate,
            override val progressTracker: ProgressTracker = TwoPartyTradeFlow.Seller.tracker()
    ) : FlowLogic<SignedTransaction>() {

        companion object {
            object AWAITING_PROPOSAL : ProgressTracker.Step("Awaiting transaction proposal")
            object VERIFYING_AND_SIGNING : ProgressTracker.Step("Verifying and signing transaction proposal") {

                override fun childProgressTracker() = SignTransactionFlow.tracker()

            }


            fun tracker() = ProgressTracker(AWAITING_PROPOSAL, VERIFYING_AND_SIGNING)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = AWAITING_PROPOSAL

            val hello = SellerTradeInfo(price, myParty)

            // send the asset we want to sell to the buyer
            subFlow(SendStateAndRefFlow(otherSideSession, listOf(assetToSell)))
            // send the price we want for the asset
            otherSideSession.send(hello)

            progressTracker.currentStep = VERIFYING_AND_SIGNING

            // Make sure we know who we are dealing with ... get the right certificate paths etc.
            subFlow(IdentitySyncFlow.Receive(otherSideSession))

            val signTransactionFlow = object : SignTransactionFlow(otherSideSession, VERIFYING_AND_SIGNING.childProgressTracker()) {

                override fun checkTransaction(stx: SignedTransaction) {
                    // Verify that we know who all the participants in the transaction are
                    val states: Iterable<ContractState> = serviceHub.loadStates(stx.tx.inputs.toSet()).map { it.state.data } + stx.tx.outputs.map { it.data }
                    states.forEach { state ->
                        state.participants.forEach { anon ->
                            require(serviceHub.identityService.wellKnownPartyFromAnonymous(anon) != null) {
                                "Transaction state $state involves an unknown participant $anon"
                            }
                        }
                    }

                    if (stx.tx.outputStates.sumCashBy(myParty.party) != price) {

                        throw FlowException("Transaction is not sending us the right amount of cash")
                    }
                }

            }

            val txId = subFlow(signTransactionFlow).id

            return waitForLedgerCommit(txId)
        }

    }

    open class Buyer(
            private val sellerSession: FlowSession,
            private val notary: Party,
            private val acceptablePrice: Amount<Currency>,
            private val typeToBuy: Class<out OwnableState>,
            private val anonymous: Boolean
    ) : FlowLogic<SignedTransaction>() {

        companion object {
            object RECEIVING: ProgressTracker.Step("Waiting to receive trade offer from seller")
            object VERIFYING: ProgressTracker.Step("Verifying the received trade offer")
            object SIGNING: ProgressTracker.Step("Signing the transaction")
            object COLLECTING_SIGNATURES: ProgressTracker.Step("Collecting the signatures of the counterparty")
            object RECORDING: ProgressTracker.Step("Recording to our ledger")
        }

        override val progressTracker = ProgressTracker(RECEIVING, VERIFYING, SIGNING, COLLECTING_SIGNATURES, RECORDING)

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = RECEIVING

            val (assetForSale, tradeRequest) = receiveAndValidateTradeRequest()

            // Create the identity we'll be paying to, and send the counterparty proof we own the identity
            val buyerAnonymousIdentity: PartyAndCertificate = if (anonymous) {
                serviceHub.keyManagementService.freshKeyAndCert(ourIdentityAndCert, false)
            } else {
                ourIdentityAndCert
            }

            progressTracker.currentStep = SIGNING

            val (ptx, cashSigningPubKeys) = assembleSharedTX(assetForSale, tradeRequest, buyerAnonymousIdentity)
            val partlySignedTx = serviceHub.signInitialTransaction(ptx, cashSigningPubKeys)

            subFlow(IdentitySyncFlow.Send(sellerSession, ptx.toWireTransaction(serviceHub)))

            progressTracker.currentStep = COLLECTING_SIGNATURES

            val sellerSignature = subFlow(CollectSignatureFlow(partlySignedTx, sellerSession, sellerSession.counterparty.owningKey))
            val fullySignedTx = partlySignedTx + sellerSignature

            progressTracker.currentStep = RECORDING

            return subFlow(FinalityFlow(fullySignedTx))
        }

        @Suspendable
        private fun receiveAndValidateTradeRequest(): Pair<StateAndRef<OwnableState>, SellerTradeInfo> {
            val assetForSale = subFlow(ReceiveStateAndRefFlow<OwnableState>(sellerSession)).single()

            // `to` creates a Pair
            return assetForSale to sellerSession.receive<SellerTradeInfo>().unwrap {
                progressTracker.currentStep = VERIFYING

                val asset = assetForSale.state.data
                val assetTypeName = asset.javaClass.name

                // the asset must be owned by the seller
                val assetForSaleIdentity = serviceHub.identityService.wellKnownPartyFromAnonymous(asset.owner)
                require(assetForSaleIdentity == sellerSession.counterparty)

                // register the identity we are about to send the payment to
                // this shouldn't be the same as the asset owner for anonymity
                val wellKnownPayToIdentity = serviceHub.identityService.verifyAndRegisterIdentity(it.payToIdentity) ?: it.payToIdentity
                require(wellKnownPayToIdentity.party == sellerSession.counterparty)

                if (it.price > acceptablePrice) {
                    throw UnacceptablePriceException(it.price)
                }

                if (!typeToBuy.isInstance(asset)) {
                    throw AssetMismatchException(typeToBuy.name, assetTypeName)
                }

                it
            }
        }

        @Suspendable
        private fun assembleSharedTX(assetForSale: StateAndRef<OwnableState>, tradeRequest: SellerTradeInfo, buyerAnonymousIdentity: PartyAndCertificate): SharedTx {
            val ptx = TransactionBuilder(notary)

            val (tx, cashSigningPubKeys) = Cash.generateSpend(serviceHub, ptx, tradeRequest.price, ourIdentityAndCert, tradeRequest.payToIdentity.party)

            tx.addInputState(assetForSale)

            val (command, state) = assetForSale.state.data.withNewOwner(buyerAnonymousIdentity.party)
            tx.addOutputState(state, assetForSale.state.contract, assetForSale.state.notary)
            tx.addCommand(command, assetForSale.state.data.owner.owningKey)

            // We set the transaction's time-window: it may be that none of the contracts need this!
            // But it can't hurt to have one.
            val currentTime = serviceHub.clock.instant()
            tx.setTimeWindow(currentTime, 30.seconds)

            return SharedTx(tx, cashSigningPubKeys)
        }

        data class SharedTx(val tx: TransactionBuilder, val cashSigningPubKeys: List<PublicKey>)

    }

}


// @questions
// + does the seller flow initiate the buyer flow?
// +    or are both started seperately ... because Buyer also truggers a subflow to receive state