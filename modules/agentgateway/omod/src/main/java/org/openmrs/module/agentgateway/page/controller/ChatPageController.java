package org.openmrs.module.agentgateway.page.controller;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The chat panel. Opens with or without a patient in context - a clinician can ask a general
 * question from anywhere, or open it from a patient's dashboard and have that patient implied.
 * <p>
 * Follows the same access pattern as every other clinical page in this deployment: the privilege
 * is checked here and the template renders an explanation instead of the panel when it fails,
 * rather than leaving the check to the endpoints alone.
 */
public class ChatPageController {

	public void controller(PageModel model,
			@RequestParam(value = "patientId", required = false) String patientUuid) {

		Patient patient = patientUuid == null ? null : Context.getPatientService().getPatientByUuid(patientUuid);
		model.addAttribute("patient", patient);
		model.addAttribute("patientUuid", patient == null ? "" : patient.getUuid());

		boolean canUse = Context.hasPrivilege(AgentGatewayPrivileges.CHAT_USE);
		model.addAttribute("accessDenied", !canUse);
		// Shown in the panel so a clinician knows up front whether the assistant can save
		// anything for them, instead of finding out only after composing a request.
		model.addAttribute("canWrite", Context.hasPrivilege(AgentGatewayPrivileges.CHAT_WRITE));
		// See ChatWidgetFragmentController: privilege AND configuration, never one alone.
		model.addAttribute("canDictate",
				Context.hasPrivilege(AgentGatewayPrivileges.VOICE_USE) && AgentGatewayConfig.isDictationConfigured());
	}
}
