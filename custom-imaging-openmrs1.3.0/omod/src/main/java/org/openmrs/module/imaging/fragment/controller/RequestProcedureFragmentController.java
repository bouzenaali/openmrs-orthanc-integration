package org.openmrs.module.imaging.fragment.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.module.imaging.api.RequestProcedureService;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.openmrs.ui.framework.annotation.FragmentParam;
import org.openmrs.ui.framework.fragment.FragmentModel;
import org.springframework.stereotype.Controller;
import org.openmrs.api.context.Context;

import java.util.List;

@Controller
public class RequestProcedureFragmentController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	public void get(FragmentModel model, @FragmentParam("patientId") Patient patient) {
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		List<RequestProcedure> requestProcedures = requestProcedureService.getRequestProcedureByPatient(patient);
		model.addAttribute("requestProceduresNo", requestProcedures.size());
	}
}
