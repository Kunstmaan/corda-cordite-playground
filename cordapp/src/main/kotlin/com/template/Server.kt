package com.template

import io.bluebank.braid.corda.BraidConfig
import io.cordite.commons.utils.Resources
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.loggerFor

@CordaService
class Server(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

    companion object {

        private val log = loggerFor<Server>()

    }

    init {
        try {
            Resources.loadResourceAsString(configFileName).let {
                log.info("found braid-config $configFileName")
                log.info("contents $it")

                it
            }
        } catch (_: Throwable) {
            log.error("cannot find braid-config $configFileName")
        }

        BraidConfig.fromResource(configFileName)?.bootstrap()
    }

    private fun BraidConfig.bootstrap() {
        this.withFlow(IOUFlow.Initiator::class)
                .withService("IOUService", IOUService(serviceHub))
                .bootstrapBraid(serviceHub)
    }

    private val configFileName: String
        get() {
            val name = serviceHub.myInfo.legalIdentities.first().name.organisation

            return "braid-$name.json"
        }

}