package org.openmrs.module.agentgateway;

import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.agentgateway.security.DelegatedTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates the delegated-token signing key pair on first start, so an administrator never has to
 * produce key material by hand. Everything else this module needs is a global property with a
 * documented default.
 */
public class AgentGatewayActivator extends BaseModuleActivator {

	private static final Logger log = LoggerFactory.getLogger(AgentGatewayActivator.class);

	@Override
	public void started() {
		try {
			if (DelegatedTokenService.ensureKeyPair()) {
				log.info("agentgateway: generated a new delegated-token signing key pair. The agent service must be "
						+ "given the matching public key before the chat can be used.");
			}
		}
		catch (Exception e) {
			// A failure here disables the chat and nothing else, which is the intended blast
			// radius - the module must never keep OpenMRS from starting.
			log.error("agentgateway: could not prepare the delegated-token signing key. The chat will be "
					+ "unavailable until this is resolved.", e);
		}
	}

	@Override
	public void stopped() {
		log.info("agentgateway stopped");
	}
}
