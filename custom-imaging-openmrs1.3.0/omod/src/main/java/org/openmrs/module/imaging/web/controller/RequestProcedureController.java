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
package org.openmrs.module.imaging.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.OrthancConfigurationService;
import org.openmrs.module.imaging.api.RequestProcedureService;
import org.openmrs.module.imaging.api.RequestProcedureStepService;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.openmrs.module.imaging.api.worklist.RequestProcedureStep;
import org.openmrs.module.imaging.web.controller.ResponseModel.ProcedureStepResponse;
import org.openmrs.module.imaging.web.controller.ResponseModel.RequestProcedureResponse;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Controller("${rootrootArtifactId}.RequestProcedureController")
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/worklist")
public class RequestProcedureController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	@RequestMapping(value = "/requests", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> useRequestProcedures(HttpServletRequest request, HttpServletResponse response) {
        RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
        RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);

        List<RequestProcedure> rps = requestProcedureService.getAllRequestProcedures();
        List<Map<String,Object>> result = new LinkedList<Map<String,Object>>();
        for (RequestProcedure rp : rps) {
            if(rp.getStatus().equalsIgnoreCase("scheduled")) {
                Map<String,Object> map = new HashMap<String,Object>();
                writeProcedure(rp, map, requestProcedureStepService);
                result.add(map);
            }
        }
        return new ResponseEntity<>(result, HttpStatus.OK);
    }
	
	/**
	 * @param rp The request procedure object
	 * @param map The worklist data map
	 * @param requestProcedureStepService The request procedure step service
	 */
	private static void writeProcedure(RequestProcedure rp, Map<String, Object> map,
	        RequestProcedureStepService requestProcedureStepService) {

		map.put("SpecificCharacterSet", "ISO_IR 100");
		map.put("AccessionNumber", rp.getAccessionNumber());
		map.put("PatientName", rp.getMrsPatient().getPersonName().getFullName());
		map.put("PatientID", rp.getMrsPatient().getPatientIdentifier().getUuid());
		String birthDate = rp.getMrsPatient().getBirthdate().toString();
		String birthAge = rp.getMrsPatient().getAge().toString();
		if (birthDate == null || birthDate.trim().isEmpty()) {
			map.put("PatientBirthDate", birthAge);
		} else {
			map.put("PatientBirthDate", birthDate);
		}
		map.put("PatientSex", rp.getMrsPatient().getGender());
		map.put("MedicalAlerts", "unknown");
		map.put("Allergies", "unknown");
		map.put("StudyInstanceUID", rp.getStudyInstanceUID());
		map.put("RequestingPhysician", rp.getRequestingPhysician()); // RequestingPhysician
		map.put("RequestedProcedureDescription", rp.getRequestDescription());
		map.put("RequestedProcedureID", rp.getId().toString());
		map.put("RequestedProcedurePriority", rp.getPriority());

		// Read the procedure step
		List<RequestProcedureStep> procedureStep = requestProcedureStepService.getAllStepByRequestProcedure(rp);
		List<Map<String, Object>> stepList = new ArrayList<>();
		for(RequestProcedureStep step : procedureStep) {
			writeProcedureStep(step, stepList);
		}
		map.put("ScheduledProcedureStepSequence", stepList);
	}
	
	/**
	 * @param step The request procedure step
	 * @param stepList The list of the procedure step
	 */
	private static void writeProcedureStep(RequestProcedureStep step, List<Map<String, Object>> stepList) {
		Map<String, Object> stepMap = new HashMap<String, Object>();
		stepMap.put("Modality", step.getModality());
		stepMap.put("ScheduledStationAETitle", step.getAetTitle());
		stepMap.put("ScheduledProcedureStepStartDate", step.getStepStartDate());
		stepMap.put("ScheduledProcedureStepStartTime", step.getStepStartTime());
		stepMap.put("ScheduledPerformingPhysicianName", step.getScheduledReferringPhysician());
		stepMap.put("PerformedProcedureStepStatus", step.getPerformedProcedureStepStatus());
		stepMap.put("ScheduledProcedureStepDescription", step.getRequestedProcedureDescription());
		stepMap.put("ScheduledProcedureStepID", step.getId().toString());
		stepMap.put("ScheduledStationName", step.getStationName());
		stepMap.put("ScheduledProcedureStepLocation", step.getProcedureStepLocation());
		stepMap.put("CommentsOnTheScheduledProcedureStep", "no value available");
		stepList.add(stepMap);
	}
	
	/**
	 * @param studyInstanceUID The dicom study instance UID
	 * @param performedProcedureStepID The OpenMRS-generated unique identifier for the part of the
	 *            procedure that has been performed in this step..
	 */
	@RequestMapping(value = "/updaterequeststatus", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public void updateRequestStatus(HttpServletRequest request, HttpServletResponse response,
	        @RequestParam(value = "studyInstanceUID") String studyInstanceUID,
	        @RequestParam(value = "performedProcedureStepID") String performedProcedureStepID) throws IOException {

		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		System.out.println("Study instances UID: " + studyInstanceUID);
		System.out.println("PerformedProcedureStepID: " + performedProcedureStepID);

		// test data
//		 performedProcedureStepID = "14";

		RequestProcedureStep step = requestProcedureStepService.getProcedureStep(Integer.parseInt(performedProcedureStepID));
		if (step != null && step.getRequestProcedure() != null) {
			// Update the procedure step status
			step.setPerformedProcedureStepStatus("completed");

			// Set the study instance UID created by modality device
			step.getRequestProcedure().setStudyInstanceUID(studyInstanceUID);
			requestProcedureStepService.updateProcedureStep(step);

			// Check all procedure step perform status of the request
			RequestProcedure requestProcedure = step.getRequestProcedure();
			List<RequestProcedureStep> stepList = requestProcedureStepService.getAllStepByRequestProcedure(requestProcedure);
			if (!stepList.isEmpty()) {
				boolean allCompleted = stepList.stream().
						allMatch(item -> "completed".equalsIgnoreCase(item.getPerformedProcedureStepStatus().trim()));

				System.out.println("All steps of procedure completed: " + allCompleted);
				if (allCompleted) {
					requestProcedure.setStatus("completed");
					requestProcedureService.updateRequestStatus(requestProcedure);
				}
			}
		}
	}
	
	/**
	 * @param requestPostData The data for the new request procedure
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/saverequest", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> saveRequestProcedure(@RequestBody Map<String, Object> requestPostData,
													  HttpServletRequest request, HttpServletResponse response ) {

		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);

		PatientService patientService = Context.getPatientService();
		String patientUuid = (String) requestPostData.get("patientUuid");
		Patient patient = patientService.getPatientByUuid(patientUuid);

		OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
		OrthancConfiguration configuration = orthancConfigurationService.getOrthancConfiguration((Integer) requestPostData.get("configurationId"));

		RequestProcedure newReq = new RequestProcedure();
		newReq.setStatus("scheduled");
		newReq.setMrsPatient(patient);
		newReq.setOrthancConfiguration(configuration);
		newReq.setAccessionNumber((String) requestPostData.get("accessionNumber"));
		newReq.setStudyInstanceUID(null);
		newReq.setRequestingPhysician((String) requestPostData.get("requestingPhysician"));
		newReq.setRequestDescription((String) requestPostData.get("requestDescription"));
		newReq.setPriority((String) requestPostData.get("priority"));
		try{
			requestProcedureService.newRequest(newReq);
			return new ResponseEntity<>("", HttpStatus.OK);
		} catch (IOException e) {
			return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}
	
	/**
	 * @param stepPostData The data for the procedure step
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/savestep", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> saveRequestProcedureStep(@RequestBody Map<String, Object> stepPostData,
													   HttpServletRequest request,
													   HttpServletResponse response ) {
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);

		int requestId = (Integer) stepPostData.get("requestId");
		RequestProcedure requestProcedure = requestProcedureService.getRequestProcedure(requestId);

		RequestProcedureStep newStep = new RequestProcedureStep();
		newStep.setRequestProcedure(requestProcedure);
		newStep.setModality((String) stepPostData.get("modality"));
		newStep.setAetTitle((String) stepPostData.get("aetTitle"));
		newStep.setScheduledReferringPhysician((String) stepPostData.get("scheduledReferringPhysician"));
		newStep.setRequestedProcedureDescription((String) stepPostData.get("requestedProcedureDescription"));
		newStep.setPerformedProcedureStepStatus("scheduled");
		newStep.setStepStartDate((String) stepPostData.get("stepStartDate"));
		newStep.setStepStartTime((String) stepPostData.get("stepStartTime"));
		newStep.setStationName((String) stepPostData.get("stationName"));
		newStep.setProcedureStepLocation((String) stepPostData.get("procedureStepLocation"));

		try{
			requestProcedureStepService.newProcedureStep(newStep);
			requestProcedure.setStatus("progress");
			requestProcedureService.updateRequestStatus(requestProcedure);

			return new ResponseEntity<>("", HttpStatus.OK);
		} catch (IOException e) {
			return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * @param patientUuid The patient unique ID
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/patientrequests", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> useRequestsByPatient(@RequestParam("patient") String patientUuid,
													   HttpServletRequest request, HttpServletResponse response ) {
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		PatientService patientService = Context.getPatientService();
		Patient patient = patientService.getPatientByUuid(patientUuid);

        List<RequestProcedure> requests = requestProcedureService.getRequestProcedureByPatient(patient);
		List<RequestProcedureResponse> requestProcedureResponseList = new ArrayList<>();
        for(RequestProcedure req : requests) {
            RequestProcedureResponse reqRes = RequestProcedureResponse.createResponse(req);
            requestProcedureResponseList.add(reqRes);
        }
        return new ResponseEntity<>(requestProcedureResponseList, HttpStatus.OK);
    }
	
	/**
	 * @param requestId The request procedure ID
	 * @return The retrieved procedure step list
	 */
	@RequestMapping(value = "/requeststep", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> useProcedureStep(@RequestParam("requestId") int requestId,
												   HttpServletRequest request,
												   HttpServletResponse response ) {
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedure req = requestProcedureService.getRequestProcedure(requestId);
		List<RequestProcedureStep> steps = requestProcedureStepService.getAllStepByRequestProcedure(req);

		List<ProcedureStepResponse> procedureStepResponseList = steps.stream().map(ProcedureStepResponse::createResponse).collect(Collectors.toList());
		return new ResponseEntity<>(procedureStepResponseList, HttpStatus.OK);
	}
	
	/**
	 * @param requestId The request procedure ID
	 * @return The response entity
	 */
	@RequestMapping(value = "/request", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> deleteRequest(@RequestParam(value="requestId") int requestId,
											   HttpServletRequest request,
											   HttpServletResponse response ) {
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedure requestProcedure = requestProcedureService.getRequestProcedure(requestId);

		List<RequestProcedureStep> stepList = requestProcedureStepService.getAllStepByRequestProcedure(requestProcedure);
		if (!stepList.isEmpty()) {
			try {
				for (RequestProcedureStep step : stepList) {
					requestProcedureStepService.deleteProcedureStep(step);
				}
			} catch (IOException e) {
				return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
			}
		}
		try {
			requestProcedureService.deleteRequestProcedure(requestProcedure);
			return new ResponseEntity<>("", HttpStatus.OK);
		}catch (IOException e) {
			return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * @param stepId The procedure step of the request
	 * @param request The request of procedure
	 * @return The response entity
	 */
	@RequestMapping(value = "/requeststep", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> deleteProcedureStep(@RequestParam(value="stepId") int stepId,
											   HttpServletRequest request,
											   HttpServletResponse response ) {

		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedureStep step = requestProcedureStepService.getProcedureStep(stepId);

		try {
			requestProcedureStepService.deleteProcedureStep(step);
			return new ResponseEntity<>("", HttpStatus.OK);
		}catch (IOException e) {
			return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
