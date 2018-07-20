package com.template

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.node.internal.StartedNode
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import org.junit.After
import org.junit.Before
import org.junit.Test

class ResolveTransactionsFlowTest {

    private lateinit var mockNet: InternalMockNetwork
    private lateinit var notaryNode: StartedNode<InternalMockNetwork.MockNode>
    private lateinit var megaCorpNode: StartedNode<InternalMockNetwork.MockNode>
    private lateinit var miniCorpNode: StartedNode<InternalMockNetwork.MockNode>

    private lateinit var notary: Party
    private lateinit var megaCorp: Party
    private lateinit var miniCorp: Party

    @Before
    fun setup() {
        mockNet = InternalMockNetwork(cordappPackages = listOf("net.corda.testing.contracts"))
        notaryNode = mockNet.defaultNotaryNode
        megaCorpNode = mockNet.createPartyNode(CordaX500Name("MegaCorp", "London", "GB"))
        miniCorpNode = mockNet.createPartyNode(CordaX500Name("MiniCorp", "London", "GB"))

        notary = notaryNode.info.singleIdentity()
        megaCorp = megaCorpNode.info.singleIdentity()
        miniCorp = miniCorpNode.info.singleIdentity()
    }

    @After
    fun teardown() {
        mockNet.stopNodes()
    }

    @Test
    fun `resolve from two hashes`() {
        // see: https://github.com/corda/corda/blob/master/core/src/test/kotlin/net/corda/core/internal/ResolveTransactionsFlowTest.kt
    }

}