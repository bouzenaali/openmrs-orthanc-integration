/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.imaging.page.controller;

import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.imaging.ImagingConstants;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.openmrs.ui.framework.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;

@Controller
public class SyncStudiesPageController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	public void get(Model model, @RequestParam(value = "patientId") Patient patient,
	        @RequestParam(value = "message") String message) {
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		List<DicomStudy> allStudies = dicomStudyService.getAllStudies();
		model.addAttribute("studies", allStudies);
		
		HashMap<String, Integer> match = new HashMap<String, Integer>();
		for (DicomStudy study : allStudies) {
			// FuzzySearch by https://github.com/xdrop/fuzzywuzzy?tab=readme-ov-file
			int score = FuzzySearch.tokenSetRatio(patient.getGivenName() + " " + patient.getFamilyName(),
			    study.getPatientName());
			match.put(study.getStudyInstanceUID(), score);
		}
		model.addAttribute("match", match);
		model.addAttribute("privilegeModifyImageData",
		    Context.getAuthenticatedUser().hasPrivilege(ImagingConstants.PRIVILEGE_MODIFY_IMAGE_DATA));
	}
	
	/**
	 * @param redirectAttributes the redirect attributes
	 * @param patient the openmrs patient
	 * @param studyId the study ID
	 * @param isChecked is the study assigned?
	 * @return the redirect url
	 */
	@RequestMapping(value = "/module/imaging/assignStudy.form", method = RequestMethod.POST)
	public String assignStudy(RedirectAttributes redirectAttributes, @RequestParam(value = "patientId") Patient patient,
	        @RequestParam(value = "studyId") int studyId, boolean isChecked) {
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		String message;
		if (isChecked) {
			dicomStudyService.setPatient(dicomStudyService.getDicomStudy(studyId), patient);
			message = "Study assigned to patient";
		} else {
			dicomStudyService.setPatient(dicomStudyService.getDicomStudy(studyId), null);
			message = "Study assignment removed";
		}
		
		redirectAttributes.addAttribute("patientId", patient.getId());
		redirectAttributes.addAttribute("message", message);
		return "redirect:/imaging/syncStudies.page";
	}
}
