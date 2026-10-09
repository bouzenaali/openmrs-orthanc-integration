package org.openmrs.module.agentgateway.page.controller;

import org.openmrs.api.context.Context;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.ui.framework.page.PageModel;

/**
 * The administrator's review screen for everything the assistant has done, and where a rollback
 * is triggered from.
 */
public class OperationLogPageController {

	public void controller(PageModel model) {
		model.addAttribute("accessDenied", !Context.hasPrivilege(AgentGatewayPrivileges.ROLLBACK));
	}
}
