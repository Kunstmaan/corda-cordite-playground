const { Proxy } = require('braid-client');

const connectToNode = (url) => {
    
    return new Promise((fulfill, reject) => {
        const node = new Proxy({ url }, onOpen, onClose, onError, { strictSSL: false })

        function onOpen() {
            node.network.myNodeInfo().then((info) => {
                console.log(`connected to ${info.legalIdentities[0].name}`);

                fulfill({node, info});
            }).catch(reject);
        }
        
        function onClose() {
            console.log('closed');
        }
        
        function onError(err) {
            console.log(`error: ${err}`);
            reject(err);
        }
    });
}

Promise.all([
    connectToNode('https://localhost:8081/api/').then((partyA) => {
        function onIOUUpdate(update) {
            console.log(`IOU Update: ${JSON.stringify(update)}`);
        }

        partyA.node.IOUService.listenForIOUUpdates(onIOUUpdate);

        return partyA;
    }),
    connectToNode('https://localhost:8082/api/')
]).then(([partyA, partyB]) => {

    return partyA.node.flows.initiator(99, partyB.info.legalIdentities[0]).then((resp) => {
        console.info(`IOU created with response: ${JSON.stringify(resp)}`);

        return resp;
    });
}).catch((err) => {

    console.error(err);
});