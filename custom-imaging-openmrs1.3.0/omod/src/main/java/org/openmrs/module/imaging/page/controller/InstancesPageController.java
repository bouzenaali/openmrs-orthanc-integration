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

import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.study.DicomInstance;
import org.openmrs.module.imaging.api.study.DicomSeries;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.openmrs.ui.framework.Model;
import org.apache.commons.logging.Log;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.List;

@Controller
public class InstancesPageController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	public void get(Model model, @RequestParam(value = "seriesInstanceUID") String seriesInstanceUID,
	        @RequestParam(value = "studyId") int studyId) throws IOException {
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		DicomStudy dicomStudy = dicomStudyService.getDicomStudy(studyId);
		List<DicomInstance> instances = dicomStudyService.fetchInstances(seriesInstanceUID, dicomStudy);
		model.addAttribute("instances", instances);
		model.addAttribute("studyInstanceUID", dicomStudy.getStudyInstanceUID());
	}
	
	/**
	 * @param orthancInstanceUID the orthanc identifier UID for the instance
	 * @param studyId the study instance UID
	 * @return the response entity
	 */
	@RequestMapping(value = "/module/imaging/previewInstance.form", method = RequestMethod.GET)
	public ResponseEntity previewInstance(@RequestParam(value = "orthancInstanceUID") String orthancInstanceUID,
	        @RequestParam(value = "studyId") int studyId) {
		
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		DicomStudy study = dicomStudyService.getDicomStudy(studyId);
		try {
			DicomStudyService.PreviewResult previewResult = dicomStudyService
			        .fetchInstancePreview(orthancInstanceUID, study);
			HttpHeaders headers = new HttpHeaders();
			headers.set("Content-type", previewResult.contentType);
			return new ResponseEntity<byte[]>(previewResult.data, headers, HttpStatus.OK);
		}
		catch (IOException e) {
			return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
