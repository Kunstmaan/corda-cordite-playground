package com.template

import net.corda.core.contracts.*
import net.corda.core.crypto.NullKeys
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.ServiceHub
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCashBy
import java.time.Instant
import java.util.*

// *****************
// * Contract Code *
// *****************

class CommercialPaper : Contract {

    companion object {
        const val CP_PROGRAM_ID: ContractClassName = "com.template.CommercialPaper"
    }

    data class State(
            val issuance: PartyAndReference,
            override val owner: AbstractParty,
            val faceValue: Amount<Issued<Currency>>,
            val maturityDate: Instant
    ) : OwnableState {

        override val participants: List<AbstractParty> get() = listOf(owner)

        fun withoutOwner() = copy(owner = AnonymousParty(NullKeys.NullPublicKey))
        override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(CommercialPaper.Commands.Move(), copy(owner = newOwner))

    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Move : TypeOnlyCommandData(), Commands
        class Redeem : TypeOnlyCommandData(), Commands
    }

    // A transaction is considered valid if the verify() function of the contract of each of the transaction's input
    // and output states does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        // Group by everything except owner: any modification to the CP at all is considered changing it fundamentally.
        val groups = tx.groupStates(State::withoutOwner)
        val cmd = tx.commands.requireSingleCommand<com.template.CommercialPaper.Commands>()

        val timeWindow: TimeWindow? = tx.timeWindow

        for ((inputs, outputs, _) in groups) {
            when (cmd.value) {
                is Commands.Issue -> {
                    val output = outputs.single()
                    val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuance must be timestamped")

                    requireThat {
                        "output states are issued by a command signer" using (output.issuance.party.owningKey in cmd.signers)
                        "output values sum to be positive" using (output.faceValue.quantity > 0)
                        "the maturity date is not in the past" using (time < output.maturityDate)

                        "cannot reissue an existing state" using (inputs.isEmpty())
                    }
                }

                is Commands.Move -> {
                    val input = inputs.single()

                    requireThat {
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in cmd.signers)
                        "the state is propagated" using (outputs.size == 1)

                        // Don't need to check anything else, as if outputs.size == 1 then the output is equal to
                        // the input ignoring the owner field due to the grouping.
                    }
                }

                is Commands.Redeem -> {
                    val input = inputs.single()

                    // sums the cash states ...
                    val received = tx.outputs.map { it.data }.sumCashBy(input.owner)
                    val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Redemption must be timestamped")

                    requireThat {
                        "the paper must have matured" using (time >= input.maturityDate)
                        "the received amount equals the face value" using (received == input.faceValue)
                        "the paper must be destroyed" using (outputs.isEmpty())
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in cmd.signers)
                    }
                }

                else -> throw IllegalArgumentException("Unrecognised command")
            }
        }
    }

    fun generateIssuance(issuance: PartyAndReference, faceValue: Amount<Issued<Currency>>, maturityDate: Instant, notary: Party): TransactionBuilder {
        val state = State(issuance, issuance.party, faceValue, maturityDate)
        val stateAndContract = StateAndContract(state, CP_PROGRAM_ID)

        return TransactionBuilder(notary).withItems(stateAndContract, Command(Commands.Issue(), issuance.party.owningKey))
    }

    fun generateMove(tx: TransactionBuilder, paper: StateAndRef<State>, newOwner: AbstractParty): TransactionBuilder {
        tx.addInputState(paper)

        val state = paper.state.data.withNewOwner(newOwner).ownableState
        val stateAndContract = StateAndContract(state, CP_PROGRAM_ID)

        return tx.withItems(stateAndContract, Command(Commands.Move(), paper.state.data.owner.owningKey))
    }

    @Throws(InsufficientBalanceException::class)
    fun generateRedeem(tx: TransactionBuilder, paper: StateAndRef<State>, services: ServiceHub, ourIdentity: PartyAndCertificate): TransactionBuilder {
        Cash.generateSpend(
                services = services,
                tx = tx,
                amount = paper.state.data.faceValue.withoutIssuer(),
                ourIdentity = ourIdentity,
                to = paper.state.data.owner
        )

        tx.addInputState(paper)
        tx.addCommand(Command(Commands.Redeem(), paper.state.data.owner.owningKey))

        return tx
    }

}