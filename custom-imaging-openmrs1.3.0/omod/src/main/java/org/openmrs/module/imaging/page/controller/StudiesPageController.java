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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.imaging.ImagingConstants;
import org.openmrs.module.imaging.ImagingProperties;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.OrthancConfigurationService;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.openmrs.ui.framework.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

@Controller
public class StudiesPageController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	public void get(Model model, @RequestParam(value = "patientId") Patient patient) {
		ImagingProperties imageProps = Context.getRegisteredComponent("imagingProperties", ImagingProperties.class);
		long maxUploadImageDataSize = imageProps.getMaxUploadImageDataSize() / 1000_000;
		
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		List<DicomStudy> studies = dicomStudyService.getStudiesOfPatient(patient);
		model.addAttribute("studies", studies);
		
		OrthancConfigurationService orthancConfigureService = Context.getService(OrthancConfigurationService.class);
		model.addAttribute("orthancConfigurations", orthancConfigureService.getAllOrthancConfigurations());
		model.addAttribute("privilegeModifyImageData",
		    Context.getAuthenticatedUser().hasPrivilege(ImagingConstants.PRIVILEGE_MODIFY_IMAGE_DATA));
		model.addAttribute("maxUploadImageDataSize", maxUploadImageDataSize);
		model.addAttribute("ohifBaseUrl", imageProps.getOhifBaseUrl());
		model.addAttribute("ohifAiBaseUrl", imageProps.getOhifAiBaseUrl());
		model.addAttribute("privilegeUseOhifAi",
		    Context.getAuthenticatedUser().hasPrivilege(ImagingConstants.PRIVILEGE_USE_OHIF_AI));
			// medreport integration hook: the GSP renders a link to medreport's own reports
			// page when that module is running. Nothing about reports is implemented here,
			// and this module keeps no dependency on medreport.
			model.addAttribute("medreportAvailable", ModuleFactory.isModuleStarted("medreport"));
	}
	
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public String handleMaxSizeException(MaxUploadSizeExceededException e, RedirectAttributes redirectAttributes,
	        @RequestParam(value = "patientId") Patient patient) {
		redirectAttributes.addAttribute("patientId", patient.getId());
		redirectAttributes.addAttribute("message", "imaging.studies.upload.maxSizeExceeded");
		redirectAttributes.addAttribute("messageType", "error");
		return "redirect:/imaging/studies.page";
	}
	
	/**
	 * @param redirectAttributes the redirect attributes
	 * @param response the http servlet response
	 * @param orthancConfigurationId the orthanc configuration ID
	 * @param files the upload files
	 * @param patient the openmrs patient
	 * @return the redirect url
	 */
	@RequestMapping(value = "/module/imaging/uploadStudy.form", method = RequestMethod.POST)
	public String uploadStudy(RedirectAttributes redirectAttributes, HttpServletResponse response,
	        @RequestParam(value = "orthancConfigurationId") int orthancConfigurationId,
	        @RequestParam("files") MultipartFile[] files, @RequestParam(value = "patientId") Patient patient) {
		log.error("Uploading " + files.length + " files");
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
		OrthancConfiguration config = orthancConfigurationService.getOrthancConfiguration(orthancConfigurationId);
		
		int numUploaded = 0; // number of successfully uploaded files
		int numFiles = 0; // number of files received from the user
		for (MultipartFile file : files) {
			if (!file.isEmpty()) {
				numFiles++;
				try {
					int status = dicomStudyService.uploadFile(config, file.getInputStream());
					if (status == 200) {
						numUploaded++; // successfully uploaded
					}
				}
				catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		String message;
		String messageType;
		if (numFiles == 0) {
			message = "imaging.studies.upload.none";
			messageType = "error";
		} else if (numUploaded == numFiles) {
			message = "imaging.studies.upload.all";
			messageType = "success";
		} else {
			message = "imaging.studies.upload.partial";
			messageType = "error";
			redirectAttributes.addAttribute("messageArg0", numUploaded);
			redirectAttributes.addAttribute("messageArg1", numFiles);
		}
		
		redirectAttributes.addAttribute("patientId", patient.getId());
		redirectAttributes.addAttribute("message", message);
		redirectAttributes.addAttribute("messageType", messageType);
		return "redirect:/imaging/studies.page";
	}
	
	/**
	 * @param redirectAttributes the redirect attributes
	 * @param orthancConfigurationId the orthanc configuration ID
	 * @param fetchOption the fetch option (all studies, new studies)
	 * @param patient the openmrs patient
	 * @return the redirect url
	 */
	@RequestMapping(value = "/module/imaging/syncStudies.form", method = RequestMethod.POST)
	public String syncStudy(RedirectAttributes redirectAttributes,
	        @RequestParam(value = "orthancConfigurationId") int orthancConfigurationId,
	        @RequestParam(value = "fetchOption") String fetchOption, @RequestParam(value = "patientId") Patient patient) {
		String message;
		String messageType;
		try {
			DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
			if (orthancConfigurationId == -1) {
				if (fetchOption.equals("all")) {
					dicomStudyService.fetchAllStudies();
				} else {
					dicomStudyService.fetchNewChangedStudies();
				}
			} else {
				OrthancConfigurationService orthancConfigurationService = Context
				        .getService(OrthancConfigurationService.class);
				OrthancConfiguration config = orthancConfigurationService.getOrthancConfiguration(orthancConfigurationId);
				if (fetchOption.equals("all")) {
					dicomStudyService.fetchAllStudies(config);
				} else {
					dicomStudyService.fetchNewChangedStudies(config);
				}
			}
			message = "imaging.studies.fetch.success";
			messageType = "success";
		}
		catch (IOException e) {
			message = "imaging.studies.fetch.error";
			messageType = "error";
		}
		
		redirectAttributes.addAttribute("patientId", patient.getId());
		redirectAttributes.addAttribute("message", message);
		redirectAttributes.addAttribute("messageType", messageType);
		return "redirect:/imaging/syncStudies.page";
	}
	
	/**
	 * @param redirectAttributes the redirect attributes
	 * @param studyId the study ID of the study to delete
	 * @param patient the openmrs patient
	 * @return the redirect url
	 */
	@RequestMapping(value = "/module/imaging/deleteStudy.form", method = RequestMethod.POST)
	public String deleteStudy(RedirectAttributes redirectAttributes, @RequestParam(value = "studyId") int studyId,
	        @RequestParam(value = "patientId") Patient patient, @RequestParam(value = "deleteOption") String deleteOption) {
		
		String message;
		String messageType;
		boolean hasPrivilege = Context.getAuthenticatedUser().hasPrivilege(ImagingConstants.PRIVILEGE_MODIFY_IMAGE_DATA);
		if (hasPrivilege) {
			DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
			DicomStudy deleteStudy = dicomStudyService.getDicomStudy(studyId);
			try {
				if (deleteOption.equals("openmrs")) {
					dicomStudyService.deleteStudyFromOpenmrs(deleteStudy);
				} else {
					dicomStudyService.deleteStudy(deleteStudy);
				}
				message = "imaging.study.delete.success";
				messageType = "success";
			}
			catch (IOException e) {
				message = "imaging.study.delete.error";
				messageType = "error";
				redirectAttributes.addAttribute("messageArg0", e.getMessage() != null ? e.getMessage() : "");
			}
		} else {
			message = "imaging.study.delete.denied";
			messageType = "error";
		}
		
		redirectAttributes.addAttribute("patientId", patient.getId());
		redirectAttributes.addAttribute("message", message);
		redirectAttributes.addAttribute("messageType", messageType);
		return "redirect:/imaging/studies.page";
	}
}
