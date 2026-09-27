/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 2.0 (the "License"); you may not use this file except in
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
package org.openmrs.module.imaging.fragment.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.openmrs.ui.framework.annotation.FragmentParam;
import org.openmrs.ui.framework.fragment.FragmentModel;
import org.springframework.stereotype.Controller;
import java.util.List;

@Controller
public class StudiesFragmentController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	public void get(FragmentModel model, @FragmentParam("patientId") Patient patient) {
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		List<DicomStudy> patientStudies = dicomStudyService.getStudiesOfPatient(patient);
		model.addAttribute("patientStudiesNo", patientStudies.size());
	}
}
