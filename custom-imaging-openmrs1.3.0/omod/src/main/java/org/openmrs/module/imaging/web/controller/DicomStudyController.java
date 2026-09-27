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
import org.openmrs.module.imaging.ImagingConstants;
import org.openmrs.module.imaging.ImagingProperties;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.OrthancConfigurationService;
import org.openmrs.module.imaging.api.match.MatchScoreCalculator;
import org.openmrs.module.imaging.api.match.MatchThresholds;
import org.openmrs.module.imaging.api.match.MatchTier;
import org.openmrs.module.imaging.api.study.DicomInstance;
import org.openmrs.module.imaging.api.study.DicomSeries;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.openmrs.module.imaging.web.controller.ResponseModel.*;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

/**
 *
 */
@Controller("${rootrootArtifactId}.DicomStudyController")
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/" + ImagingConstants.MODULE_ID)
public class DicomStudyController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	/**
	 * @param patientUuid The openMRS unique patient ID
	 * @return The dicom studies
	 */
	@RequestMapping(value = "/studies", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> useStudiesByPatient(@RequestParam(value="patient") String patientUuid, HttpServletRequest request,
                                             HttpServletResponse response ) {
        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        PatientService patientService = Context.getPatientService();
        Patient patient = patientService.getPatientByUuid(patientUuid);
        List<DicomStudy> studies = dicomStudyService.getStudiesOfPatient(patient);
        List<DicomStudyResponse> responseList = DicomStudyResponse.createResponse(studies);

        return new ResponseEntity<>(responseList, HttpStatus.OK);
    }
	
	/**
	 * @param configurationId The configuration ID
	 * @param patientUuid The patient unique ID
	 * @return The study list with scores matched to the patient.
	 */
	@RequestMapping(value = "/studiesbyconfig", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> useStudiesByConfig(@RequestParam(value="configurationId") int configurationId,
                                                     @RequestParam(value="patient") String patientUuid,
                                                     HttpServletRequest request, HttpServletResponse response ) {
        PatientService patientService = Context.getPatientService();
        Patient patient = patientService.getPatientByUuid(patientUuid);

        OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
        OrthancConfiguration configuration = orthancConfigurationService.getOrthancConfiguration(configurationId);

        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        List<DicomStudy> studies = dicomStudyService.getStudiesByConfiguration(configuration);

        StudiesWithScoreResponse studiesWithScore = new StudiesWithScoreResponse();
        studiesWithScore.studies = DicomStudyResponse.createResponse(studies);
        studiesWithScore.scores = new HashMap<String,Integer>();
        for (DicomStudy study : studies) {
            // Combines fuzzy name similarity with any available DICOM PatientID/PatientBirthDate
            // signals - see DicomStudyServiceImpl#computeMatchScore.
            int score = dicomStudyService.computeMatchScore(patient, study);
            studiesWithScore.scores.put(study.getStudyInstanceUID(), score);
        }

        ImagingProperties imagingProperties = Context.getRegisteredComponent("imagingProperties", ImagingProperties.class);
        MatchThresholds thresholds = imagingProperties.getMatchThresholds();
        Map<String, MatchTier> tiers = MatchScoreCalculator.classify(studiesWithScore.scores, thresholds);
        studiesWithScore.tiers = new HashMap<String, String>();
        for (Map.Entry<String, MatchTier> entry : tiers.entrySet()) {
            studiesWithScore.tiers.put(entry.getKey(), entry.getValue().name());
        }

        return new ResponseEntity<>(studiesWithScore, HttpStatus.OK);
    }
	
	/**
	 * @param studyId The dicom series of the study
	 * @return The dicom series
	 */
	@RequestMapping(value = "/studyseries", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> useStudySeries(@RequestParam(value = "studyId") int studyId,
                                                     HttpServletRequest request, HttpServletResponse response ) {
        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        try {
            DicomStudy study = dicomStudyService.getDicomStudy(studyId);
            List<DicomSeries> seriesList = dicomStudyService.fetchSeries(study);

            List<DicomSeriesResponse> seriesResponseList = new ArrayList<>();
            for (DicomSeries ser : seriesList) {
                DicomSeriesResponse res = DicomSeriesResponse.createResponse(ser);
                seriesResponseList.add(res);
            }
            return new ResponseEntity<>(seriesResponseList, HttpStatus.OK);
        } catch (IOException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
	
	/**
	 * @param seriesInstanceUID The dicom series ID of the series
	 * @param studyId The study ID
	 * @return All instances of the series
	 */
	@RequestMapping(value = "/studyinstances", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> useStudyInstances(@RequestParam(value="studyId") int studyId,
                                                    @RequestParam(value="seriesInstanceUID") String seriesInstanceUID,
                                                    HttpServletRequest request, HttpServletResponse response ) {

        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        try{
            DicomStudy study = dicomStudyService.getDicomStudy(studyId);
            List<DicomInstance> instances = dicomStudyService.fetchInstances(seriesInstanceUID, study);
            List<DicomInstanceResponse> instanceResponseList = new ArrayList<>();

            for (DicomInstance instance : instances) {
                DicomInstanceResponse dicomInstanceResponse = DicomInstanceResponse.createResponse(instance);
                instanceResponseList.add(dicomInstanceResponse);
            }
            return new ResponseEntity<>(instanceResponseList, HttpStatus.OK);
        } catch (IOException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
	
	/**
	 * @return All the configured servers
	 */
	@RequestMapping(value = "/configurations", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> useOrthancConfigurations(HttpServletRequest request, HttpServletResponse response ) {
        OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
        List<OrthancConfiguration> configurations = orthancConfigurationService.getAllOrthancConfigurations();
        List<OrthancConfigurationResponse> orthancConfigurationResponseList = OrthancConfigurationResponse.configurationResponseList(configurations);
        return new ResponseEntity<>(orthancConfigurationResponseList, HttpStatus.OK);
    }
	
	/**
	 * @param file The DICOM study files that should be uploaded to the Orthanc server."
	 * @param configurationId The configuration ID
	 * @throws IOException
	 */
	@RequestMapping(value = "/instances", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> uploadStudies(    @RequestParam(value="file") MultipartFile file,
                                                    @RequestParam(value="configurationId") int configurationId,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response ) throws IOException {
        OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
        OrthancConfiguration configuration = orthancConfigurationService.getOrthancConfiguration(configurationId);
        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        dicomStudyService.uploadFile(configuration, file.getInputStream());
        return new ResponseEntity<>("", HttpStatus.OK);
    }
	
	/**
	 * @param configurationId The configured server ID where the studies are stored.
	 * @param fetchOption options: (all, newest)
	 */
	@RequestMapping(value="/linkstudies", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> getLinkStudies(@RequestParam(value="configurationId") int configurationId,
                                                 @RequestParam(value="fetchOption") String fetchOption,
                                                 HttpServletRequest request,
                                                 HttpServletResponse response ) {
        OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
        OrthancConfiguration configuration = orthancConfigurationService.getOrthancConfiguration(configurationId);
        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);

        try {
            if (fetchOption.equals("all")) {
                dicomStudyService.fetchAllStudies(configuration);
            } else {
                dicomStudyService.fetchNewChangedStudies(configuration);
            }
            return new ResponseEntity<>("", HttpStatus.OK);
        } catch (IOException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
	
	/**
	 * @param studyId The dicom study ID
	 * @param patientUuid The openmrs patient unique ID
	 * @param isAssign The study is for the patient.
	 */
	@RequestMapping(value="/assingstudy", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> assignStudy(@RequestParam(value="studyId") int studyId,
                                              @RequestParam(value="patient") String patientUuid,
                                              @RequestParam(value="isAssign") boolean isAssign,
                                              HttpServletRequest request,
                                              HttpServletResponse response ) {
        PatientService patientService = Context.getPatientService();
        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        DicomStudy study = dicomStudyService.getDicomStudy(studyId);
        Patient patient = patientService.getPatientByUuid(patientUuid);

        // Check if studyId or patientUuid is missing
        if (studyId <= 0|| patientUuid == null || patientUuid.trim().isEmpty()) {
            return new ResponseEntity<>("studyId or patient UUID is missing", HttpStatus.BAD_REQUEST);
        }
        if (isAssign) {
            study.setMrsPatient(patient);
        } else {
            study.setMrsPatient(null);
        }
        return new ResponseEntity<>("", HttpStatus.OK);
    }
	
	/**
	 * @param studyId The dicom study ID
	 * @param deleteOption Options: (from openmrs, from openmrs and orthanc)
	 */
	@RequestMapping(value = "/study", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> deleteStudy(@RequestParam(value="studyId") int studyId,
                                              @RequestParam(value="deleteOption") String deleteOption,
                                              HttpServletRequest request,
                                              HttpServletResponse response ) {
        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        DicomStudy study = dicomStudyService.getDicomStudy(studyId);
        try {
            if (deleteOption.equals("openmrs")) {
                dicomStudyService.deleteStudyFromOpenmrs(study);
            } else {
                dicomStudyService.deleteStudy(study);
            }
            return new ResponseEntity<>("", HttpStatus.OK);
        }catch (IOException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
	
	/**
	 * @param orthancSeriesUID The unique series ID generated by the Orthanc server
	 * @param studyId The study ID
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/series", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> deleteSeries(@RequestParam(value="orthancSeriesUID") String orthancSeriesUID,
                                               @RequestParam(value="studyId") int studyId,
                                              HttpServletRequest request,
                                              HttpServletResponse response ) {
        DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
        try {
            DicomStudy study = dicomStudyService.getDicomStudy(studyId);
            dicomStudyService.deleteSeries(orthancSeriesUID, study);
            return new ResponseEntity<>("", HttpStatus.OK);
        }catch (IOException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
	
	/**
	 * @param orthancInstanceUID The unique series ID generated by the Orthanc server
	 * @param studyId The study ID
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/previewinstance", method = RequestMethod.GET)
	@Transactional
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
