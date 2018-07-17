package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

// *****************
// * API Endpoints *
// *****************
@Path("template")
class TemplateApi(val rpcOps: CordaRPCOps) {

    // Accessible at /api/template/templateGetEndpoint.
    @GET
    @Path("templateGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.ok("Template GET endpoint.").build()
    }

}

// *********
// * Flows *
// *********

@InitiatingFlow
@StartableByRPC
class IOUFlow(val iouValue: Int, val otherParty: Party) : FlowLogic<Unit>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call() {
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        val outputState = IOUState(iouValue, ourIdentity, otherParty)
        val cmd = Command(IOUContract.Commands.Action(), ourIdentity.owningKey)

        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(outputState, IOU_CONTRACT_ID)
                .addCommand(cmd)

        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        subFlow(FinalityFlow(signedTx))
    }

}

// ***********
// * Plugins *
// ***********
class TemplateWebPlugin : WebServerPluginRegistry {

    // A list of lambdas that create objects exposing web JAX-RS REST APIs.
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TemplateApi))
    //A list of directories in the resources directory that will be served by Jetty under /web.
    // This template's web frontend is accessible at /web/template.
    override val staticServeDirs: Map<String, String> = mapOf(
        // This will serve the templateWeb directory in resources to /web/template
        "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )

}

// Serialization whitelist.
class TemplateSerializationWhitelist : SerializationWhitelist {

    override val whitelist: List<Class<*>> = listOf(TemplateData::class.java)

}

// This class is not annotated with @CordaSerializable, so it must be added to the serialization whitelist, above, if
// we want to send it to other nodes within a flow.
data class TemplateData(val payload: String)
