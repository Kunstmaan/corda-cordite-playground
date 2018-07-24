package com.template

import io.bluebank.braid.corda.services.transaction
import io.bluebank.braid.core.annotation.MethodDescription
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.Vault
import rx.Observable

class IOUService(private val serviceHub: AppServiceHub) {

    @MethodDescription(description = "listens for IOU state updates in the vault", returnType = Vault.Update::class)
    fun listenForIOUUpdates() : Observable<Vault.Update<IOUState>> {

        return serviceHub.transaction {
            serviceHub.vaultService.trackBy(IOUState::class.java).updates
        }
    }

}