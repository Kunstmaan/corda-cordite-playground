package com.template

import io.cordite.dgl.corda.token.issuedBy
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.days
import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.CASH
import net.corda.finance.contracts.asset.Cash
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.internal.TEST_TX_TIME
import org.junit.Test
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import net.corda.testing.node.makeTestIdentityService
import net.corda.testing.node.transaction

private val megaCorp = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
private val dummyNotary = TestIdentity(DUMMY_NOTARY_NAME, 20)
private val alice = TestIdentity(ALICE_NAME, 70)
private val bob = TestIdentity(BOB_NAME, 50)

class CommercialPaperTest {

    fun getPaper() = CommercialPaper.State(
            issuance = megaCorp.ref(123),
            owner = megaCorp.party,
            faceValue = 1000.DOLLARS issuedBy megaCorp.ref(123),
            maturityDate = TEST_TX_TIME + 7.days
    )

    private val ledgerServices = MockServices(
            cordappPackages = listOf("net.corda.finance.contracts"),
            initialIdentity = megaCorp,
            identityService = makeTestIdentityService(megaCorp.identity)
    )

    @Test
    fun emptyLedger() {
        ledgerServices.ledger {
        }
    }

    @Test(expected = IllegalStateException::class)
    fun simpleCPDoesntCompile() {
        val inState = getPaper()

        ledgerServices.ledger(notary = dummyNotary.party) {
            transaction {
                attachments(CommercialPaper.CP_PROGRAM_ID)
                input(CommercialPaper.CP_PROGRAM_ID, inState)

                verifies()
            }
        }
    }

    @Test
    fun simpleCPMoveFailureAndSuccess() {
        val inState = getPaper()
        ledgerServices.ledger(notary = dummyNotary.party) {
            transaction {
                attachments(CommercialPaper.CP_PROGRAM_ID)
                input(CommercialPaper.CP_PROGRAM_ID, inState)
                command(megaCorp.publicKey, CommercialPaper.Commands.Move())

                `fails with`("the state is propagated")

                output(CommercialPaper.CP_PROGRAM_ID, "alice's paper", inState.withNewOwner(alice.party).ownableState)

                verifies()
            }
        }
    }

    @Test
    fun `simple issuance with tweak`() {
        // you don't need that ledger DSL
        ledgerServices.transaction(dummyNotary.party) {
            output(CommercialPaper.CP_PROGRAM_ID, "paper", getPaper())
            attachments(CommercialPaper.CP_PROGRAM_ID)

            tweak {
                command(alice.publicKey, CommercialPaper.Commands.Issue())
                timeWindow(TEST_TX_TIME)

                `fails with`("output states are issued by a command signer")
            }

            command(megaCorp.publicKey, CommercialPaper.Commands.Issue())
            timeWindow(TEST_TX_TIME)

            verifies()
        }
    }

    @Test
    fun `chain commercial paper`() {
        val issuer = megaCorp.ref(123)
        ledgerServices.ledger(dummyNotary.party) {
            unverifiedTransaction {
                attachment(Cash.PROGRAM_ID)
                // shortcut didn't work issuedBy ... ownedBy ...
                output(Cash.PROGRAM_ID, "alice's 900$", 900.DOLLARS.CASH issuedBy issuer ownedBy alice.party)
            }

            transaction("Issuance") {
                output(CommercialPaper.CP_PROGRAM_ID, "paper", getPaper())
                command(megaCorp.publicKey, CommercialPaper.Commands.Issue())
                attachments(CommercialPaper.CP_PROGRAM_ID)
                timeWindow(TEST_TX_TIME)

                verifies()
            }

            transaction("Trade to Alice") {
                input("paper")
                input("alice's 900$")
                output(Cash.PROGRAM_ID, "borrowed 900$", 900.DOLLARS.CASH issuedBy issuer ownedBy megaCorp.party)
                output(CommercialPaper.CP_PROGRAM_ID, "alice's paper", "paper".output<CommercialPaper.State>().withNewOwner(alice.party).ownableState)
                command(alice.publicKey, Cash.Commands.Move())
                command(megaCorp.publicKey, CommercialPaper.Commands.Move())

                verifies()
            }

            tweak {
                transaction("Trade to Bob") {
                    input("paper")
                    output(CommercialPaper.CP_PROGRAM_ID, "bob's paper", "paper".output<CommercialPaper.State>().withNewOwner(bob.party).ownableState)
                    command(megaCorp.publicKey, CommercialPaper.Commands.Move())

                    verifies()
                }

                // we double spent the CP
                fails()
            }

            verifies()
        }
    }

}