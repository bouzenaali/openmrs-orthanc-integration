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
import org.openmrs.api.context.Context;
import org.openmrs.module.imaging.ImagingConstants;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.OrthancConfigurationService;
import org.openmrs.module.imaging.api.RequestProcedureService;
import org.openmrs.module.imaging.api.RequestProcedureStepService;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.openmrs.module.imaging.api.worklist.RequestProcedureStep;
import org.openmrs.ui.framework.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.RequestContextUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

@Controller
public class ImagingSettingsPageController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	public void get(Model model, HttpServletRequest request) {
		OrthancConfigurationService orthancConfigureService = Context.getService(OrthancConfigurationService.class);
		model.addAttribute("orthancConfigurations", orthancConfigureService.getAllOrthancConfigurations());
		model.addAttribute("privilegeManagerOrthancConfiguration",
		    Context.getAuthenticatedUser().hasPrivilege(ImagingConstants.TASK_MANAGER_ORTHANC_CONFIGURATION));
		model.addAttribute("pageMessage", resolveMessage(request));
	}
	
	/**
	 * Resolves the banner message for one render only.
	 * <p>
	 * Flash attributes are preferred because they survive exactly one redirect. The ?message= query
	 * parameter is still read, for links already in circulation, but nothing sets it any more: as a
	 * URL parameter it stuck in the address bar and redisplayed on every later render, so a failed
	 * delete looked like it was being caused by whatever the user did next.
	 * <p>
	 * The result is HTML-escaped here rather than in the page: it can come straight from the query
	 * string, and the page renders it unescaped.
	 */
	private String resolveMessage(HttpServletRequest request) {
		Object msg = null;
		Map<String, ?> flash = RequestContextUtils.getInputFlashMap(request);
		if (flash != null) {
			msg = flash.get("message") != null ? flash.get("message") : flash.get("error");
		}
		if (msg == null) {
			msg = request.getParameter("message");
		}
		return msg == null ? "" : escapeHtml(msg.toString());
	}
	
	private static String escapeHtml(String s) {
		StringBuilder out = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&':
					out.append("&amp;");
					break;
				case '<':
					out.append("&lt;");
					break;
				case '>':
					out.append("&gt;");
					break;
				case '"':
					out.append("&quot;");
					break;
				case '\'':
					out.append("&#39;");
					break;
				default:
					out.append(c);
			}
		}
		return out.toString();
	}
	
	/**
	 * @param redirectAttributes the redirect attributes for direct to another page
	 * @param url the orthanc url
	 * @param username the orthanc user name
	 * @param password the orthanc user password
	 * @return the response status code
	 */
	@RequestMapping(value = "/module/imaging/storeConfiguration.form", method = RequestMethod.POST)
	public String storeConfiguration(RedirectAttributes redirectAttributes, @RequestParam(value = "url") String url,
	        @RequestParam(value = "proxyurl") String proxyurl, @RequestParam(value = "username") String username,
	        @RequestParam(value = "password") String password) {
		OrthancConfigurationService orthancConfigureService = Context.getService(OrthancConfigurationService.class);
		url = url.trim();
		username = username.trim();
		password = password.trim();
		if (proxyurl != null) {
			proxyurl = proxyurl.trim();
		}
		proxyurl = proxyurl.trim();
		if (!url.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
			OrthancConfiguration oc = new OrthancConfiguration();
			oc.setOrthancBaseUrl(url);
			oc.setOrthancProxyUrl(proxyurl);
			oc.setOrthancUsername(username);
			oc.setOrthancPassword(password);
			try {
				orthancConfigureService.saveOrthancConfiguration(oc);
			}
			catch (Exception ex) {
				redirectAttributes.addFlashAttribute("message", "Saving configuration failed: " + ex.getMessage());
			}
		} else {
			redirectAttributes.addFlashAttribute("message", "Saving orthanc configuration failed");
		}
		return "redirect:/imaging/imagingSettings.page";
	}
	
	/**
	 * Updates an existing configuration in place.
	 * <p>
	 * Until 1.4.0 the page could only add and delete, so changing a password meant deleting the
	 * configuration - which is refused as soon as any study references it. There was therefore no
	 * way to rotate PACS credentials from the UI at all.
	 * 
	 * @param id the configuration to update
	 * @param password the new password, or blank to keep the stored one
	 * @return a redirect back to the settings page
	 */
	@RequestMapping(value = "/module/imaging/updateConfiguration.form", method = RequestMethod.POST)
	public String updateConfiguration(RedirectAttributes redirectAttributes, @RequestParam(value = "id") int id,
	        @RequestParam(value = "url") String url, @RequestParam(value = "proxyurl", required = false) String proxyurl,
	        @RequestParam(value = "username") String username,
	        @RequestParam(value = "password", required = false) String password) {
		// Enforced here, not only by hiding the button: the form posts to a plain URL.
		if (!Context.getAuthenticatedUser().hasPrivilege(ImagingConstants.TASK_MANAGER_ORTHANC_CONFIGURATION)) {
			redirectAttributes.addFlashAttribute("message",
			    "You do not have permission to change the Orthanc configuration.");
			return "redirect:/imaging/imagingSettings.page";
		}
		OrthancConfigurationService orthancConfigureService = Context.getService(OrthancConfigurationService.class);
		OrthancConfiguration oc = orthancConfigureService.getOrthancConfiguration(id);
		if (oc == null) {
			redirectAttributes.addFlashAttribute("message", "Configuration not found.");
			return "redirect:/imaging/imagingSettings.page";
		}
		url = url == null ? "" : url.trim();
		username = username == null ? "" : username.trim();
		proxyurl = proxyurl == null ? "" : proxyurl.trim();
		password = password == null ? "" : password.trim();
		if (url.isEmpty() || username.isEmpty()) {
			redirectAttributes.addFlashAttribute("message", "URL and user name are required.");
			return "redirect:/imaging/imagingSettings.page";
		}
		oc.setOrthancBaseUrl(url);
		oc.setOrthancProxyUrl(proxyurl);
		oc.setOrthancUsername(username);
		// Blank means "leave it alone". The form never renders the stored password, so an
		// empty field is the normal case when only the URL or user name is being changed.
		if (!password.isEmpty()) {
			oc.setOrthancPassword(password);
		}
		try {
			// updateOrthancConfiguration, NOT saveOrthancConfiguration: the latter routes to
			// dao.saveNew(), which rejects a duplicate orthancBaseUrl. On an edit the existing
			// row IS the duplicate, so saving an unchanged URL failed with "A configuration
			// with the same base URL already exists" - which made rotating the credentials,
			// the whole point of this form, impossible.
			orthancConfigureService.updateOrthancConfiguration(oc);
			redirectAttributes.addFlashAttribute("message", "Configuration updated.");
		}
		catch (Exception ex) {
			log.error("Failed to update Orthanc configuration with ID " + id, ex);
			redirectAttributes.addFlashAttribute("message", "Updating configuration failed: " + ex.getMessage());
		}
		return "redirect:/imaging/imagingSettings.page";
	}
	
	/**
	 * @param redirectAttributes the redirect attributes for direct to another page
	 * @param id the configuration id
	 * @return the status of the delete orthanc configuration
	 */
	@RequestMapping(value = "/module/imaging/deleteConfiguration.form", method = RequestMethod.POST)
	public String deleteConfiguration(RedirectAttributes redirectAttributes, @RequestParam(value = "id") int id) {
		OrthancConfigurationService orthancConfigureService = Context.getService(OrthancConfigurationService.class);
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		OrthancConfiguration config = orthancConfigureService.getOrthancConfiguration(id);
		
		if (config == null) {
			redirectAttributes.addFlashAttribute("error", "Configuration not found.");
			return "redirect:/imaging/imagingSettings.page";
		}
		
		boolean hasStudy = !dicomStudyService.getStudiesByConfiguration(config).isEmpty();
		List<RequestProcedure> requestProcedureList = requestProcedureService.getRequestProcedureByConfig(config);
		if (hasStudy || !requestProcedureList.isEmpty()) {
			redirectAttributes
			        .addFlashAttribute("message",
			            "The configuration can not be deleted because there is at least one study or request procedure referring to it");
		} else {
			try {
				orthancConfigureService.removeOrthancConfiguration(config);
				redirectAttributes.addFlashAttribute("message", "Configuration deleted successfully.");
			}
			catch (Exception e) {
				log.error("Failed to delete configuration with ID " + id, e);
				redirectAttributes.addFlashAttribute("error", "An error occurred while deleting the configuration.");
			}
		}
		return "redirect:/imaging/imagingSettings.page";
	}
	
	/**
	 * @param response the response
	 * @param url the orthanc url
	 * @param username the orthanc user name
	 * @param password the orthanc user password
	 */
	@RequestMapping(value = "/module/imaging/checkConfiguration.form", method = RequestMethod.GET)
	@ResponseBody
	public void checkConfiguration(HttpServletResponse response, @RequestParam(value = "url") String url,
	        @RequestParam(value = "proxyurl") String proxyurl, @RequestParam(value = "username") String username,
	        @RequestParam(value = "password") String password) {
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		String checkUrl = (proxyurl != null && !proxyurl.isEmpty()) ? proxyurl : url;
		try {
			try {
				int status = dicomStudyService.testOrthancConnection(checkUrl, username, password);
				if (status == 200) {
					response.getOutputStream().print("Check successful. The Orthanc server responded correctly.");
				} else {
					response.getOutputStream().print("Check failed. The server responded with error " + status);
				}
			}
			catch (MalformedURLException e) {
				response.getOutputStream().print("The URL is not well formed.");
			}
			catch (UnknownHostException e) {
				response.getOutputStream().print("The server could not be reached.");
			}
			catch (IOException e) {
				response.getOutputStream().print(e.getMessage());
			}
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
