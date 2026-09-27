<%
    ui.decorateWith("appui", "standardEmrPage",  [ title: ui.message("imaging.app.imageStudies.title") ])
    ui.includeCss("imaging", "general.css")
    ui.includeCss("imaging", "studies.css")
%>
<script type="text/javascript">
    var breadcrumbs = [
        { icon: "icon-home", link: '/' + OPENMRS_CONTEXT_PATH + '/index.htm' },
        { label: "${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.familyName))) }, ${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.givenName))) }",
            link: '${ui.pageLink("coreapps", "clinicianfacing/patient", [patientId: patient.id])}'},
        { label: "${ ui.message("imaging.studies") }" }
    ];
</script>

${ ui.includeFragment("coreapps", "patientHeader", [ patient: patient ]) }
${ ui.includeFragment("uicommons", "infoAndErrorMessage")}
<% ui.includeJavascript("imaging", "sortable.min.js") %>
<% ui.includeJavascript("imaging", "filter_table.js")%>

<h2>
    ${ ui.message("imaging.studies") }
</h2>

<%
    def statusMessageKey = param["message"]?.getAt(0)
    def statusMessageType = param["messageType"]?.getAt(0)
    def statusMessageArgs = []
    if (param["messageArg0"]) { statusMessageArgs << param["messageArg0"].getAt(0) }
    if (param["messageArg1"]) { statusMessageArgs << param["messageArg1"].getAt(0) }
    def statusMessageText = statusMessageKey ? (statusMessageArgs ? ui.message(statusMessageKey, statusMessageArgs) : ui.message(statusMessageKey)) : ""
    def statusMessageColor = statusMessageType == "success" ? "green" : "red"
%>
<% if (statusMessageText) { %>
<div style="color:${statusMessageColor};">
${statusMessageText}
</div>
<% } %>

<script>
    function togglePopupUpload() {
        const overlay = document.getElementById('popupOverlayUpload');
        overlay.classList.toggle('show');
    }

    function toggleSynchronizeStudies() {
        const overlay = document.getElementById('popupOverlaySynchronization');
        overlay.classList.toggle('show');
    }

    function togglePopupDeleteStudy(studyId, patient) {
        const overlay = document.getElementById('popupOverlayDeleteStudy');
        overlay.classList.toggle('show');
        document.deleteStudyForm.action = "/${contextPath}/module/imaging/deleteStudy.form?studyId="
                                             + studyId
                                             + "&patientId=" + patient;
    }

</script>

<div>
    <% if (orthancConfigurations.size() == 0) { %>
        No Orthanc server configured
    <% } else { %>
        <% if (privilegeModifyImageData) { %>
            <button class="btn-open-popup-upload" onclick="togglePopupUpload()">Upload Study</button>
        <% } %>
        <button class="btn-open-popup-sync" onclick="toggleSynchronizeStudies()">Get Studies</button>
    <% } %>
</div>

<%
    // ---------------------------------------------------------------------------------
    // medreport entry point.
    //
    // Placed ABOVE the studies table on purpose. An earlier version embedded the whole
    // reports panel at the bottom of this page, which failed twice over: clinicians never
    // scrolled far enough to see it, and it disappeared entirely on the "Get studies"
    // navigation. A banner at the top linking to medreport's own page fixes both, and gives
    // the per-row action below somewhere stable to point at.
    //
    // The guard keeps this page working unchanged when medreport is not installed. No report
    // logic lives here; the imaging module still knows nothing about reports.
    // ---------------------------------------------------------------------------------
    if (medreportAvailable) {
%>
    <% ui.includeCss("medreport", "medreport.css") %>
    <div class="mr-entry-banner">
        <div>
            <strong>${ ui.message("medreport.imaging.openPage") }</strong>
            <span class="mr-entry-hint">${ ui.message("medreport.imaging.openPageHint") }</span>
        </div>
        <a class="mr-btn mr-btn-primary"
           href="${ ui.pageLink('medreport', 'imagingReports', [patientId: patient.id]) }">
            ${ ui.message("medreport.imaging.openPage") }
        </a>
    </div>
<% } %>

<div id="table-scroll">
    <table id="studies" class="table table-sm table-responsive-sm table-responsive-md table-responsive-lg table-responsive-xl" data-sortable>
        <thead class="imaging-table-thead">
            <script src="filter_table.js" defer></script>
            <tr>
                <th>${ ui.message("imaging.app.studyInstanceUid.label")}</th>
                <th>${ ui.message("imaging.app.patientName.label")}</th>
                <th>${ ui.message("imaging.app.date.label")}</th>
                <th>${ ui.message("imaging.app.description.label")}</th>
                <th>${ ui.message("imaging.app.server.label")}</th>
                <th data-no-filter style="width: max-content;">${ ui.message("coreapps.actions") }</th>
            </tr>
        </thead>
        <tbody>
            <% if (studies.size() == 0) { %>
                <tr>
                    <td colspan="6" align="center">${ui.message("imaging.studies.none")}</td>
                </tr>
            <% } %>
            <% studies.each { study ->
                def baseUrl = study.orthancConfiguration.orthancProxyUrl?.trim() ? study.orthancConfiguration.orthancProxyUrl : study.orthancConfiguration.orthancBaseUrl
                def normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl[0..-2] : baseUrl
            %>
                <tr>
                    <td class="uid-td">
                        <a href="${ui.pageLink("imaging", "series", [patientId: patient.id, studyId: study.id])}">${ui.format(study.studyInstanceUID)}</a>
                    </td>
                    <td>${ui.format(study.patientName)}</td>
                    <td>${ui.format(study.studyDate)}</td>
                    <td class="description-td">${ui.format(study.studyDescription)}</td>
                    <td>${ui.format(study.orthancConfiguration.orthancBaseUrl)}</td>
                     <td>
                        <% if (privilegeModifyImageData) { %>
                            <a class="delete-study"
                                onclick="togglePopupDeleteStudy('${study.id}', '${patient.id}')"><i class="icon-remove delete-action"></i>
                            </a>
                        <% } %>
                        <div style="display: flex">
                           <a href="${normalizedBaseUrl}/stone-webviewer/index.html?study=${ui.format(study.studyInstanceUID)}" title="${ui.message("imaging.app.openStoneView.label") }">
                                <img class="stone-img" alt="Show image in stone viewer" src="${ ui.resourceLink("imaging", "images/stoneViewer.png")}"/></a>
                           <% if (ohifBaseUrl?.trim()) {
                               def normalizedOhifBaseUrl = ohifBaseUrl.endsWith("/") ? ohifBaseUrl[0..-2] : ohifBaseUrl
                           %>
                           <a href="${normalizedOhifBaseUrl}/viewer?StudyInstanceUIDs=${ui.format(study.studyInstanceUID)}" title="${ ui.message("imaging.app.openOHIFView.label") }" target="_blank">
                               <img class="ohif-img" alt="Show image in OHIF viewer" src="${ ui.resourceLink("imaging", "images/ohifViewer.png")}"/></a>
                           <% } %>
                           <% /* OHIF-AI: a second, independent viewer. Hidden unless the URL is set AND
                                 the user holds the privilege - its output reaches the PACS. */ %>
                           <% if (privilegeUseOhifAi && ohifAiBaseUrl?.trim()) {
                               def normalizedOhifAiBaseUrl = ohifAiBaseUrl.endsWith("/") ? ohifAiBaseUrl[0..-2] : ohifAiBaseUrl
                           %>
                           <a href="${normalizedOhifAiBaseUrl}/viewer?StudyInstanceUIDs=${ui.format(study.studyInstanceUID)}" title="${ ui.message("imaging.app.openOhifAiView.label") }" target="_blank">
                               <img class="ohif-ai-img" alt="Show image in the AI viewer" src="${ ui.resourceLink("imaging", "images/ohifAiViewer.png")}"/></a>
                           <% } %>
                           <a href="${normalizedBaseUrl}/ui/app/#/filtered-studies?StudyInstanceUID=${ui.format(study.studyInstanceUID)}&expand=study" title="${ ui.message("imaging.app.orthancExplorer.label") }">
                               <img class="orthanc-img" alt="Show image data in Orthanc explorer" src="${ ui.resourceLink("imaging", "images/orthanc.png")}"/></a>
                       </div>
                    </td>
                </tr>
            <% } %>
        </tbody>
    </table>
</div>
<div id="popupOverlayUpload" class="overlay-container">
    <div class="popup-box">
        <h2 style="color: #009384;">Upload study</h2>
        <form class="form-container" enctype='multipart/form-data' method='POST' action='/${contextPath}/module/imaging/uploadStudy.form?patientId=${patient.id}'>
            <label class="form-label" for="server">Select Orthanc server</label>
            <select class="select-config" id="orthancConfigurationId" name="orthancConfigurationId">
                <% orthancConfigurations.each { config -> %>
                    <option value="${config.id}">${ui.format(config.orthancBaseUrl)}</option>
                <% } %>
            </select>
            <label class="form-label" for="files" style="color: red;">Select files to upload (dicom files or zip files containing dicom files. Note: You cannot upload more than ${ui.format(maxUploadImageDataSize)} MB in one go!)</label>
            <input class="form-input" type='file' name='files' multiple>
            <div class="popup-box-btn">
                <button class="btn-submit" type="submit">Upload</button>
                <button class="btn-close-popup" type="button" onclick="togglePopupUpload()">Cancel</button>
            </div>
        </form>
    </div>
</div>

<div id="popupOverlaySynchronization" class="overlay-container">
    <div class="popup-box">
        <h2 style="color: #009384;">Fetch studies from providers</h2>
        <form class="form-container" method='POST' action='/${contextPath}/module/imaging/syncStudies.form?patientId=${patient.id}'>
            <div class="radio-div">
                <label style="margin-right: 30px; color: #5B57A6;">
                    <input style="width:20px; margin-top: 2px; height: 20px;" type="radio" id="fetchAll" name="fetchOption" value="all" checked>Get all studies</label>
                <label style="margin-left: 30px; color: #5B57A6;">
                    <input style="width:20px; margin-top: 2px; height: 20px;" type="radio" id="fetchNewest" name="fetchOption" value="newest">Get the latest studies</label>
            </div>
            <label class="form-label" for="server">Select Orthanc server</label>
            <select class="select-config" id="orthancConfigurationId" name="orthancConfigurationId">
                <option value="-1">All servers</option>
                <% orthancConfigurations.each { config -> %>
                    <option value="${config.id}">${ui.format(config.orthancBaseUrl)}</option>
                <% } %>
            </select>
            <div class="popup-box-btn">
                <button class="btn-submit" type="submit">Start</button>
                <button class="btn-close-popup" type="button" onclick="toggleSynchronizeStudies()">Cancel</button>
            </div>
        </form>
    </div>
</div>

<div id="popupOverlayDeleteStudy" class="overlay-container">
    <div class="popup-box" style="width: 65%;">
        <h2>Delete study</h2>
        <form name="deleteStudyForm" class="form-container" method='POST'>
            <h2 id="deleteStudyMessage">${ ui.message("imaging.deleteStudy.message") }</h3>
            <div class="radio-div">
                <label style="margin-right: 30px; color: #5B57A6">
                    <input style="width:20px; margin-top: 2px; height: 20px;" type="radio" id="deleteOpenmrs" name="deleteOption" value="openmrs" checked>Delete from OpenMRS</label>
                <label style="margin-left: 30px; color: #5B57A6;">
                    <input style="width:20px; margin-top: 2px; height: 20px;" type="radio" id="deleteBoth" name="deleteOption" value="openmrsOrthanc">Delete from OpenMRS and Orthanc</label>
            </div>
            <div class="popup-box-btn" style="margin-top: 40px;">
                <button class="btn-submit" type="submit">${ ui.message("imaging.action.delete") }</button>
                <button class="btn-close-popup" type="button" onclick="togglePopupDeleteStudy()">Cancel</button>
            </div>
        </form>
    </div>
</div>
















