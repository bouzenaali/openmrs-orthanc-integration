/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.imaging.extension.html;

import org.openmrs.module.web.extension.AdministrationSectionExt;
import org.openmrs.module.Extension;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This class defines the links that will appear on the administration page under the
 * "imaging.title" heading. This extension is enabled by defining (uncommenting) it in the
 * config.xml file.
 */
public class AdminList extends AdministrationSectionExt {
	
	/**
	 * @see AdministrationSectionExt#getMediaType()
	 */
	public Extension.MEDIA_TYPE getMediaType() {
		return Extension.MEDIA_TYPE.html;
	}
	
	/**
	 * @see AdministrationSectionExt#getTitle()
	 */
	public String getTitle() {
		return "imaging.title";
	}
	
	/**
	 * @see AdministrationSectionExt#getLinks()
	 */
	public Map<String, String> getLinks() {
		
		//		Map<String, String> map = new HashMap<String, String>();
		LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
		map.put("imaging/imagingSettings.page", "Orthanc settings");
		
		return map;
	}
	
}
