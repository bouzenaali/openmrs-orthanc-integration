# This file is part of [Integration of Orthanc with OpenMRS].
#
# Integration of Orthanc with OpenMRS is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# Integration of Orthanc with OpenMRS is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with [Integration of Orthanc with OpenMRS]. If not, see <https://www.gnu.org/licenses/>.

import json
import orthanc
import requests


def OnWorkList(answers, query, issuerAet, calledAet):
    # Get query in json format and write it to log
    queryDicom = query.WorklistGetDicomQuery()
    queryJson = json.loads(orthanc.DicomBufferToJson(
        queryDicom, orthanc.DicomToJsonFormat.SHORT, orthanc.DicomToJsonFlags.NONE, 0))
    orthanc.LogWarning('C-FIND worklist request: %s' %
                       json.dumps(queryJson, indent = 4))

    response = requests.get(getWorklistURL, auth=(worklistUsername, worklistPassword))
    responseJson = response.json()

    orthanc.LogWarning('Response by server: %s' % json.dumps(responseJson))

    for dicomJson in responseJson:
        responseDicom = orthanc.CreateDicom(json.dumps(dicomJson), None, orthanc.CreateDicomFlags.NONE)

        orthanc.LogWarning(orthanc.DicomBufferToJson(
            responseDicom, orthanc.DicomToJsonFormat.SHORT, orthanc.DicomToJsonFlags.NONE, 0))

        # Thie code only for test:
        # Save the DICOM buffer to a file
        # with open("/tmp/worklist_test.wl", 'wb') as f:
        #     f.write(responseDicom)

        if query.WorklistIsMatch(responseDicom):
            answers.WorklistAddAnswer(query, responseDicom)

def OnChange(changeType, level, resource):
    # Handle new study
    if changeType == orthanc.ChangeType.STABLE_STUDY:
        try:
            studyJson = json.loads(orthanc.RestApiGet("/studies/"+resource))
            studyInstanceUID = studyJson["MainDicomTags"]["StudyInstanceUID"]
            if "Series" in studyJson:
                for seriesID in studyJson["Series"]:
                    stepID = None
                    seriesJson = json.loads(orthanc.RestApiGet("/series/" + seriesID))
                    if "PerformedProcedureStepID" in seriesJson:
                        stepID = seriesJson["PerformedProcedureStepID"]
                    if stepID is None and "Instances" in seriesJson and len(seriesJson["Instances"])>0:
                        instanceJson = json.loads(orthanc.RestApiGet("/instances/" + seriesJson["Instances"][0] + "/tags?simplify"))
                        if "PerformedProcedureStepID" in instanceJson:
                            stepID = instanceJson["PerformedProcedureStepID"]

                    orthanc.LogWarning("Step ID of stable series of study " + studyInstanceUID + ": "+str(stepID))
                    if stepID is not None:
                        try:
                            postUrl = updateRequestStatusURL+"?studyInstanceUID=" + studyInstanceUID + "&performedProcedureStepID=" + str(stepID)
                            response = requests.post(postUrl, auth=(worklistUsername, worklistPassword))
                            response.raise_for_status()
                        except requests.RequestException as e:
                            orthanc.LogError(f"Failed to update procedure step status: {str(e)}")
        except requests.RequestException as e:
            orthanc.LogError(f"Failed to process stable study: {str(e)}")
    else:
        return None

def getConfigItem(configItemName):
    config = orthanc.GetConfiguration()
    configJson = json.loads(config)
    url = configJson[configItemName]
    return url

orthanc.RegisterWorklistCallback(OnWorkList)
orthanc.RegisterOnChangeCallback(OnChange)

# Read the API URL from the configuration of Orthanc
getWorklistURL = getConfigItem("ImagingWorklistURL")
updateRequestStatusURL = getConfigItem("ImagingUpdateRequestStatus")
worklistUsername = getConfigItem("ImagingWorklistUsername")
worklistPassword = getConfigItem("ImagingWorklistPassword")


    
        
