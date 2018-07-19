package com.template

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.node.services.Permissions
import net.corda.testing.core.*
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.junit.Test
import kotlin.test.assertEquals

class IntegrationTest {

    @Test
    fun `start nodes test`() {
        driver(DriverParameters(
                isDebug = true,
                startNodesInProcess = true,
                extraCordappPackagesToScan = listOf("net.corda.finance.contracts.asset")
        )) {
            val aliceUser = User("aliceUser", "testPassword1", permissions = setOf(
                    Permissions.startFlow<CashIssueFlow>(),
                    Permissions.startFlow<CashPaymentFlow>(),
                    Permissions.invokeRpc("vaultTrackBy"),
                    Permissions.invokeRpc(CordaRPCOps::notaryIdentities),
                    Permissions.invokeRpc(CordaRPCOps::networkMapFeed)
            ))
            val bobUser = User("bobUser", "testPassword2", permissions = setOf(
                    Permissions.startFlow<CashPaymentFlow>(),
                    Permissions.invokeRpc("vaultTrackBy"),
                    Permissions.invokeRpc(CordaRPCOps::networkMapFeed)
            ))
            val (alice, bob) = listOf(
                    startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)),
                    startNode(providedName = BOB_NAME, rpcUsers = listOf(bobUser))
            ).transpose().getOrThrow()

            val aliceClient = CordaRPCClient(alice.rpcAddress)
            val aliceProxy = aliceClient.start("aliceUser", "testPassword1").proxy

            val bobClient = CordaRPCClient(bob.rpcAddress)
            val bobProxy = bobClient.start("bobUser", "testPassword2").proxy

            // normally these default properties are provided ...
            val bobVaultUpdates = bobProxy.vaultTrackBy<Cash.State>(
                    criteria = QueryCriteria.VaultQueryCriteria(),
                    paging = PageSpecification(),
                    sorting = Sort(emptySet()),
                    contractStateType = Cash.State::class.java
            ).updates
            val aliceVaultUpdates = aliceProxy.vaultTrackBy<Cash.State>(
                    criteria = QueryCriteria.VaultQueryCriteria(),
                    paging = PageSpecification(),
                    sorting = Sort(emptySet()),
                    contractStateType = Cash.State::class.java
            ).updates

            val issueRef = OpaqueBytes.of(0)
            val notaryParty = aliceProxy.notaryIdentities().first()
            (1..10).map { i ->
                aliceProxy.startFlow(::CashIssueFlow,
                        i.DOLLARS,
                        issueRef,
                        notaryParty
                ).returnValue
            }.transpose().getOrThrow()

            (1..10).map { i ->
                aliceProxy.startFlow(::CashPaymentFlow,
                        i.DOLLARS,
                        bob.nodeInfo.singleIdentity(),
                        true
                ).returnValue
            }.transpose().getOrThrow()

            bobVaultUpdates.expectEvents {
                parallel(
                        (1..10).map { i ->
                            expect(
                                    match = { update: Vault.Update<Cash.State> ->
                                        update.produced.first().state.data.amount.quantity == i * 100L
                                    }
                            ) { update ->
                                println("Bob vault update of $update")
                            }
                        }
                )
            }

            for (i in 1..10) {
                bobProxy.startFlow(::CashPaymentFlow,
                        i.DOLLARS,
                        alice.nodeInfo.singleIdentity()
                ).returnValue.getOrThrow()
            }

            aliceVaultUpdates.expectEvents {
                sequence(
                        (1..10).map { i ->
                            expect { update: Vault.Update<Cash.State> ->
                                println("Alice got vault update of $update")
                                assertEquals(update.produced.first().state.data.amount.quantity, i * 100L)
                            }
                        }
                )
            }
        }
    }
}