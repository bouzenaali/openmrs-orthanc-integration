package org.openmrs.module.agentgateway.fragment.controller;

import org.apache.commons.lang.StringUtils;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.ui.framework.fragment.FragmentModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;

/**
 * The assistant, embedded directly in the patient dashboard's second column.
 * <p>
 * A chat the clinician has to navigate away to find is a chat nobody uses. This fragment is
 * included into the dashboard through the {@code patientDashboard.secondColumnFragments}
 * extension point - the same mechanism the imaging module already uses on this installation - so
 * the assistant is on screen with the patient already in context.
 * <p>
 * <b>The patient is read from the request, not from a framework-injected argument.</b> The
 * neighbouring imaging fragment declares an {@code org.openmrs.Patient} parameter and relies on
 * the UI framework converting the dashboard's {@code PatientDomainWrapper} into one. That works,
 * but it is a binding this module would be guessing at, and a fragment controller that throws
 * renders an error box on a screen clinicians use for everything else. Reading {@code patientId}
 * off the request cannot fail: every page that includes this fragment already carries it, and if
 * it somehow does not, the widget still renders and the assistant simply asks which patient is
 * meant - which is a conversation it already knows how to have.
 */
public class ChatWidgetFragmentController {

	private static final Logger log = LoggerFactory.getLogger(ChatWidgetFragmentController.class);

	public void controller(FragmentModel model, HttpServletRequest request) {
		Patient patient = resolvePatient(request);

		model.addAttribute("patientUuid", patient == null ? "" : patient.getUuid());
		model.addAttribute("canUse", Context.hasPrivilege(AgentGatewayPrivileges.CHAT_USE));
		model.addAttribute("canWrite", Context.hasPrivilege(AgentGatewayPrivileges.CHAT_WRITE));
		// Both, not either: the privilege says this clinician may dictate, the configuration says
		// there is somewhere to dictate to. A microphone button that cannot work is worse than no
		// microphone button, so it is not rendered at all unless both hold.
		model.addAttribute("canDictate",
				Context.hasPrivilege(AgentGatewayPrivileges.VOICE_USE) && AgentGatewayConfig.isDictationConfigured());
	}

	/**
	 * The dashboard passes a UUID; some older links pass the internal integer id. Both are
	 * accepted, and anything unrecognised yields no patient rather than an exception.
	 */
	private Patient resolvePatient(HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		String patientId = request.getParameter("patientId");
		if (StringUtils.isBlank(patientId)) {
			return null;
		}

		try {
			Patient patient = Context.getPatientService().getPatientByUuid(patientId.trim());
			if (patient != null) {
				return patient;
			}
			if (StringUtils.isNumeric(patientId.trim())) {
				return Context.getPatientService().getPatient(Integer.valueOf(patientId.trim()));
			}
		}
		catch (Exception e) {
			log.debug("agentgateway: could not resolve the patient for the chat widget", e);
		}
		return null;
	}
}
