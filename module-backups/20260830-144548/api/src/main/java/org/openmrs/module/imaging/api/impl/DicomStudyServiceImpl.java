/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.imaging.api.impl;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.OrthancConfigurationService;
import org.openmrs.module.imaging.api.dao.DicomStudyDao;
import org.openmrs.module.imaging.api.study.DicomInstance;
import org.openmrs.module.imaging.api.study.DicomSeries;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

@Transactional
public class DicomStudyServiceImpl extends BaseOpenmrsService implements DicomStudyService {
	
	protected final Log log = LogFactory.getLog(this.getClass());
	
	private DicomStudyDao dao;
	
	/**
	 * @param dao the dao to set
	 */
	public void setDao(DicomStudyDao dao) {
		this.dao = dao;
	}
	
	/**
	 * @return the dao
	 */
	public DicomStudyDao getDao() {
		return dao;
	}
	
	/**
	 * @param config the orthanc server configuration
	 * @param con the http url connection
	 * @throws IOException the IO exception
	 */
	private static void throwConnectionException(OrthancConfiguration config, HttpURLConnection con) throws IOException {
		throw new IOException("Request to Orthanc server " + config.getOrthancBaseUrl() + " failed with error "
		        + con.getResponseCode() + " " + con.getResponseMessage());
	}
	
	/**
	 * @param method the request method
	 * @param url the url
	 * @param path the connection path
	 * @param username the user name
	 * @param password the user password
	 * @return The request connection
	 * @throws IOException IO connection
	 */
	private HttpURLConnection getOrthancConnection(String method, String url, String path, String username, String password)
	        throws IOException {
		String encoding = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
		URL serverURL = URI.create(url).resolve(path).toURL();
		HttpURLConnection con = (HttpURLConnection) serverURL.openConnection();
		con.setRequestMethod(method);
		con.setRequestProperty("Authorization", "Basic " + encoding);
		con.setUseCaches(false);
		return con;
	}
	
	/**
	 * @param con http url request connection
	 * @param query the query string
	 * @throws IOException IO exception
	 */
	private void sendOrthancQuery(HttpURLConnection con, String query) throws IOException {
		con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		con.setRequestProperty( "charset", "utf-8");
		con.setDoOutput(true);
		byte[] data = query.getBytes(StandardCharsets.UTF_8);
		con.setRequestProperty( "Content-Length", Integer.toString(data.length));
		try(DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
			wr.write(data);
		}
	}
	
	/**
	 * @param url the Url
	 * @param username the user name
	 * @param password the password
	 * @return the response status
	 * @throws IOException the IO exception
	 */
	@Override
	public int testOrthancConnection(String url, String username, String password) throws IOException {
		HttpURLConnection con = getOrthancConnection("GET", url, "/system", username, password);
		int status = con.getResponseCode();
		con.disconnect();
		return status;
	}
	
	/**
	 * @throws IOException the IO exception
	 */
	@Override
	public void fetchAllStudies() throws IOException {
		OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
		List<OrthancConfiguration> configs = orthancConfigurationService.getAllOrthancConfigurations();
		for (OrthancConfiguration config : configs) {
			fetchAllStudies(config);
		}
	}
	
	/**
	 * @param config the configuration
	 * @throws IOException the IO exception
	 */
	@Override
	public void fetchAllStudies(OrthancConfiguration config) throws IOException {
		log.info("Fetching all studies from orthanc server " + config.getOrthancBaseUrl());
		HttpURLConnection con = getOrthancConnection("POST", config.getOrthancBaseUrl(), "/tools/find",
		    config.getOrthancUsername(), config.getOrthancPassword());
		sendOrthancQuery(con, "{" + "\"Level\": \"Studies\"," + " \"Expand\": true," + " \"Query\": {}" + " }");
		int status = con.getResponseCode();
		if (status == HttpURLConnection.HTTP_OK) {
			JsonNode studiesData = new ObjectMapper().readTree(con.getInputStream());
			for (JsonNode studyData : studiesData) {
				createOrUpdateStudy(config, studyData);
			}
		} else {
			throwConnectionException(config, con);
		}
	}
	
	/**
	 * @param config the orthanc configuration
	 * @param studyData the patient image study data
	 */
	private void createOrUpdateStudy(OrthancConfiguration config, JsonNode studyData) {
		String studyInstanceUID = studyData.path("MainDicomTags").path("StudyInstanceUID").getTextValue();
		String orthancStudyUID = studyData.path("ID").getTextValue();
		String patientName = studyData.path("PatientMainDicomTags").path("PatientName").getTextValue();
		String studyDate = Optional.ofNullable(studyData.path("MainDicomTags").path("StudyDate").getTextValue()).orElse("");
		String studyTime = Optional.ofNullable(studyData.path("MainDicomTags").path("StudyTime").getTextValue()).orElse("");
		String studyDescription = Optional.ofNullable(
		    studyData.path("MainDicomTags").path("StudyDescription").getTextValue()).orElse("");
		String gender = Optional.ofNullable(studyData.path("PatientMainDicomTags").path("Gender").getTextValue()).orElse("");
		DicomStudy study = new DicomStudy(studyInstanceUID, orthancStudyUID, null, config, patientName, studyDate,
		        studyTime, studyDescription, gender);
		
		DicomStudy existingStudy = dao.getByStudyInstanceUID(config, studyInstanceUID);
		// new study? -> save new
		if (existingStudy == null) {
			dao.save(study);
		} else {
			// existing study? -> update
			// DICOM studies are immutable. Only the Orthanc ID can change.
			existingStudy.setOrthancStudyUID(study.getOrthancStudyUID());
			dao.save(existingStudy);
		}
	}
	
	/**
	 * @param config the orthanc configuration
	 * @param is the input strem
	 * @return the respose code
	 * @throws IOException the IO exception
	 */
	@Override
	public int uploadFile(OrthancConfiguration config, InputStream is) throws IOException {
		HttpURLConnection con = getOrthancConnection("POST", config.getOrthancBaseUrl(), "/instances",
		    config.getOrthancUsername(), config.getOrthancPassword());
		con.setRequestProperty("Content-Type", "application/dicom");
		con.setDoOutput(true);
		IOUtils.copy(is, con.getOutputStream());
		return con.getResponseCode();
	}
	
	/**
	 * @param pt the openmrs patient
	 * @return the list of studies
	 */
	@Override
	public List<DicomStudy> getStudiesOfPatient(Patient pt) {
		return dao.getByPatient(pt);
	}
	
	public List<DicomStudy> getStudiesByConfiguration(OrthancConfiguration config) {
		return dao.getByConfiguration(config);
	}
	
	/**
	 * @return the list dicom studies
	 */
	@Override
	public List<DicomStudy> getAllStudies() {
		return dao.getAll();
	}
	
	/**
	 * @throws IOException the IO exception
	 */
	@Override
	public void fetchNewChangedStudies() throws IOException {
		OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
		List<OrthancConfiguration> configs = orthancConfigurationService.getAllOrthancConfigurations();
		for (OrthancConfiguration config : configs) {
			fetchNewChangedStudies(config);
		}
	}
	
	/**
	 * @param config The orthanc configuation
	 * @throws IOException the IO exception
	 */
	@Override
	public void fetchNewChangedStudies(OrthancConfiguration config) throws IOException {
		// repeat until all updates have been received
		while(true) {
			// get changes from server
			String params = "?limit=1000";
			if (config.getLastChangedIndex() != -1) {
				params += "&since=" + config.getLastChangedIndex();
			}
			HttpURLConnection con = getOrthancConnection("GET", config.getOrthancBaseUrl(), "/changes" + params,
					config.getOrthancUsername(), config.getOrthancPassword());
			int status = con.getResponseCode();
			if (status == HttpURLConnection.HTTP_OK) {
				// collect changes
				JsonNode changesData = new ObjectMapper().readTree(con.getInputStream());
				JsonNode changes = changesData.get("Changes");
				List<String> orthancStudyIds = new ArrayList<>();
				for (JsonNode change : changes) {
					String changeType = change.get("ChangeType").getTextValue();
					if (changeType.equals("NewStudy") || changeType.equals("StableStudy")) {
						orthancStudyIds.add(change.get("ID").getTextValue());
					}
				}
				// update the studies
				fetchNewChangedStudies(config, orthancStudyIds);
				// remember last processed change
				OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
				config.setLastChangedIndex(changesData.get("Last").asInt());
				orthancConfigurationService.updateOrthancConfiguration(config);
				// stop when all changes read
				if(changesData.get("Done").asText().equals("true")) {
					break;
				}
			} else {
				throwConnectionException(config, con);
			}
		}
	}
	
	/**
	 * @param config the orthanc configuration
	 * @param orthancStudyIds the study instance UIDs
	 * @throws IOException the IO exception
	 */
	private void fetchNewChangedStudies(OrthancConfiguration config, List<String> orthancStudyIds) throws IOException {
		for (String orthancStudyId : orthancStudyIds) {
			HttpURLConnection con = getOrthancConnection("GET", config.getOrthancBaseUrl(), "/studies/" + orthancStudyId,
			    config.getOrthancUsername(), config.getOrthancPassword());
			
			// Enable connection reuse (Keep-Alive)
			con.setRequestProperty("Connection", "keep-alive");
			
			int status = con.getResponseCode();
			if (status == HttpURLConnection.HTTP_OK) {
				JsonNode studyData = new ObjectMapper().readTree(con.getInputStream());
				createOrUpdateStudy(config, studyData);
			} else {
				throwConnectionException(config, con);
			}
			
			// Close input stream to free connection for reuse
			con.getInputStream().close();
			con.disconnect();
		}
	}
	
	@Override
	public DicomStudy getDicomStudy(int id) {
		return dao.get(id);
	}
	
	/**
	 * @param studyInstanceUID the study instance UID
	 * @return the dicom study
	 */
	@Override
	public DicomStudy getDicomStudy(OrthancConfiguration config, String studyInstanceUID) {
		return dao.getByStudyInstanceUID(config, studyInstanceUID);
	}
	
	/**
	 * @param study the dicom study
	 * @param patient the openmrs patient
	 */
	@Override
	public void setPatient(DicomStudy study, Patient patient) {
		study.setMrsPatient(patient);
		dao.save(study);
	}
	
	/**
	 * @param dicomStudy the dicom study
	 */
	@Override
	public void deleteStudy(DicomStudy dicomStudy) throws IOException {
		OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
		OrthancConfiguration config = orthancConfigurationService.getOrthancConfiguration(dicomStudy
		        .getOrthancConfiguration().getId());
		HttpURLConnection con = getOrthancConnection("DELETE", config.getOrthancBaseUrl(),
		    "/studies/" + dicomStudy.getOrthancStudyUID(), config.getOrthancUsername(), config.getOrthancPassword());
		int responseCode = con.getResponseCode();
		if (responseCode == HttpURLConnection.HTTP_OK || responseCode == 404) {
			dao.remove(dicomStudy);
		} else {
			throw new IOException("Failed to delete DICOM study. Response Code: " + responseCode + ", Study UID: "
			        + dicomStudy.getOrthancStudyUID());
		}
	}
	
	@Override
	public void deleteStudyFromOpenmrs(DicomStudy dicomStudy) {
		dao.remove(dicomStudy);
	}
	
	/**
	 * @param seriesOrthancUID the series of the dicom study
	 * @param seriesStudy the dicom study
	 */
	@Override
	public void deleteSeries(String seriesOrthancUID, DicomStudy seriesStudy) throws IOException {
		OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
		OrthancConfiguration config = orthancConfigurationService.getOrthancConfiguration(seriesStudy
		        .getOrthancConfiguration().getId());
		HttpURLConnection con = getOrthancConnection("DELETE", config.getOrthancBaseUrl(), "/series/" + seriesOrthancUID,
		    config.getOrthancUsername(), config.getOrthancPassword());
		int responseCode = con.getResponseCode();
		if (responseCode != HttpURLConnection.HTTP_OK) {
			throw new IOException("Failed to delete DICOM series. Response Code: " + responseCode + ", Series UID: "
			        + seriesOrthancUID);
		}
	}
	
	/**
	 * @param study the study
	 * @return the list of series of the dicom study
	 * @throws IOException the IO exception
	 */
	@Override
	public List<DicomSeries> fetchSeries(DicomStudy study) throws IOException {
		List<DicomSeries> seriesList = new ArrayList<>();

		OrthancConfiguration config = study.getOrthancConfiguration();
		HttpURLConnection con = getOrthancConnection("POST", config.getOrthancBaseUrl(), "/tools/find",
				config.getOrthancUsername(), config.getOrthancPassword());
		sendOrthancQuery(con, "{" + "\"Level\": \"Series\"," + " \"Expand\": true," + " \"Query\": {\"StudyInstanceUID\":\"" + study.getStudyInstanceUID() + "\"}" + " }");
		int status = con.getResponseCode();
		if (status == HttpURLConnection.HTTP_OK) {
			JsonNode seriesesData = new ObjectMapper().readTree(con.getInputStream());
			for (JsonNode seriesData : seriesesData) {
				String seriesInstanceUID = seriesData.path("MainDicomTags").path("SeriesInstanceUID").getTextValue();
				String orthancSeriesUID = seriesData.path("ID").getTextValue();
				String seriesDescription = Optional.ofNullable(seriesData.path("MainDicomTags").path("SeriesDescription").getTextValue()).orElse("");
				String seriesNumber = seriesData.path("MainDicomTags").path("SeriesNumber").getTextValue();
				String modality = seriesData.path("MainDicomTags").path("Modality").getTextValue();
				String seriesDate = Optional.ofNullable(seriesData.path("MainDicomTags").path("SeriesDate").getTextValue()).orElse("");
				String seriesTime = Optional.ofNullable(seriesData.path("MainDicomTags").path("SeriesTime").getTextValue()).orElse("");
				DicomSeries series = new DicomSeries(seriesInstanceUID, orthancSeriesUID, config, seriesDescription, seriesNumber, modality, seriesDate, seriesTime);
				seriesList.add(series);
			}
		} else {
			throw new IOException("Request to Orthanc server " + config.getOrthancBaseUrl() + " failed with error "
					+ con.getResponseCode() + " " + con.getResponseMessage());
		}
		return seriesList;
	}
	
	/**
	 * @param seriesInstanceUID the series instance UID
	 * @return the list of the series of the dicom study
	 * @throws IOException the IO exception
	 */
	@Override
	public List<DicomInstance> fetchInstances(String seriesInstanceUID, DicomStudy study) throws IOException {
		List<DicomInstance> instanceList = new ArrayList<>();

		OrthancConfiguration config = study.getOrthancConfiguration();
		HttpURLConnection con = getOrthancConnection("POST", config.getOrthancBaseUrl(), "/tools/find",
				config.getOrthancUsername(), config.getOrthancPassword());
		sendOrthancQuery(con, "{" + "\"Level\": \"Instance\"," + " \"Expand\": true," + " \"Query\": {\"SeriesInstanceUID\":\"" + seriesInstanceUID + "\"}" + " }");
		int status = con.getResponseCode();
		if (status == HttpURLConnection.HTTP_OK) {
			JsonNode instancesData = new ObjectMapper().readTree(con.getInputStream());
			for (JsonNode instanceData : instancesData) {
				String sopInstanceUID = instanceData.path("MainDicomTags").path("SOPInstanceUID").getTextValue();
				String orthancInstanceUID = instanceData.path("ID").getTextValue();
				String instanceNumber = instanceData.path("MainDicomTags").path("InstanceNumber").getTextValue();
				String imagePositionPatient = Optional.ofNullable(instanceData.path("MainDicomTags").path("ImagePositionPatient").getTextValue()).orElse("");
				String numberOfFrames = Optional.ofNullable(instanceData.path("MainDicomTags").path("NumberOfFrames").getTextValue()).orElse("");
				DicomInstance instance = new DicomInstance(sopInstanceUID, orthancInstanceUID, instanceNumber, imagePositionPatient, numberOfFrames, config);
				instanceList.add(instance);
			}
		} else {
			throw new IOException("Request to Orthanc server " + config.getOrthancBaseUrl() + " failed with error "
					+ con.getResponseCode() + " " + con.getResponseMessage());
		}
		return instanceList;
	}
	
	/**
	 * @param orthancInstanceUID the orthanc identifier UID
	 * @param study the dicom study
	 * @return the preview image
	 * @throws IOException the IO exception
	 */
	@Override
	public PreviewResult fetchInstancePreview(String orthancInstanceUID, DicomStudy study) throws IOException {
		OrthancConfiguration config = study.getOrthancConfiguration();
		HttpURLConnection con = getOrthancConnection("GET", config.getOrthancBaseUrl(), "/instances/" + orthancInstanceUID
		        + "/preview", config.getOrthancUsername(), config.getOrthancPassword());
		int responseCode = con.getResponseCode();
		if (responseCode == HttpURLConnection.HTTP_OK) {
			// read image
			InputStream inputStream = con.getInputStream();
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, bytesRead);
			}
			
			PreviewResult result = new PreviewResult();
			result.data = outputStream.toByteArray();
			result.contentType = con.getContentType();
			return result;
		} else {
			throw new IOException("Request to Orthanc server " + config.getOrthancBaseUrl() + " failed with error "
			        + con.getResponseCode() + " " + con.getResponseMessage());
		}
	}
	
}
