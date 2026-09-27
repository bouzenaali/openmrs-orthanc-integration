<%
    ui.decorateWith("appui", "standardEmrPage",  [ title: ui.message("imaging.app.studySeries.title") ])
    ui.includeCss("imaging", "general.css")
    ui.includeCss("imaging", "series.css")
%>

<script type="text/javascript">
    var breadcrumbs = [
        { icon: "icon-home", link: '/' + OPENMRS_CONTEXT_PATH + '/index.htm' },
        { label: "${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.familyName))) }, ${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.givenName))) }",
            link: '${ui.pageLink("coreapps", "clinicianfacing/patient", [patientId: patient.id])}'},
        { label: "${ ui.message("imaging.studies") }",
            link: '${ui.pageLink("imaging", "studies", [patientId: patient.id])}'},
        { label: "${ ui.message("imaging.series") }" }
    ];
</script>


${ ui.includeFragment("coreapps", "patientHeader", [ patient: patient ]) }
${ ui.includeFragment("uicommons", "infoAndErrorMessage")}
<% ui.includeJavascript("imaging", "sortable.min.js") %>
<% ui.includeJavascript("imaging", "filter_table.js")%>

<h2>
    ${ ui.message("imaging.series") }
</h2>

<div style="color:red;">
${param["message"]?.getAt(0) ?: ""}
</div>

<script>
    function togglePopupDeleteSeries(orthancSeriesUID, studyId, patient) {
        const overlay = document.getElementById('popupOverlayDeleteSeries');
        overlay.classList.toggle('show');
        document.deleteSeriesForm.action = "/${contextPath}/module/imaging/deleteSeries.form?orthancSeriesUID="
                                             + orthancSeriesUID
                                             + "&studyId=" + studyId
                                             + "&patientId=" + patient;
    }
</script>

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
           href="${ ui.pageLink('medreport', 'imagingReports', [patientId: patient.id, studyUid: orthancStudyUID]) }">
            ${ ui.message("medreport.imaging.openPage") }
        </a>
    </div>
<% } %>

<div id="table-scroll">
    <table id="series" class="table table-sm table-responsive-sm table-responsive-md table-responsive-lg table-responsive-xl" data-sortable>
        <thead class="imaging-table-thead">
            <script src="filter_table.js" defer></script>
            <tr>
                <th>${ ui.message("imaging.app.seriesInstanceUID.label")}</th>
                <th>${ ui.message("imaging.app.seriesNumber.label")}</th>
                <th>${ ui.message("imaging.app.description.label")}</th>
                <th>${ ui.message("imaging.app.date.label")}</th>
                <th>${ ui.message("imaging.app.modality.label")}</th>
                <th  data-no-filter style="width: 70px;">${ ui.message("coreapps.actions") }</th>
            </tr>
        </thead>
        <tbody>
            <% if (serieses.size() == 0) { %>
                <tr>
                    <td colspan="6" align="center">${ui.message("imaging.series.none")}</td>
                </tr>
            <% } %>
            <% serieses.each { series ->
                def baseUrl = series.orthancConfiguration.orthancProxyUrl?.trim() ? series.orthancConfiguration.orthancProxyUrl : series.orthancConfiguration.orthancBaseUrl
                def normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl[0..-2] : baseUrl
            %>
                <tr>
                    <td class="uid-td">
                        <a href="${ui.pageLink("imaging", "instances", [patientId: patient.id, seriesInstanceUID: series.seriesInstanceUID, studyId: studyId])}">${ui.format(series.seriesInstanceUID)}</a>
                    </td>
                    <td>${ui.format(series.seriesNumber)}</td>
                    <td>${ui.format(series.seriesDescription)}</td>
                    <td>${ui.format(series.seriesDate)}</td>
                    <td>${ui.format(series.modality)}</td>
                     <td>
                         <% if (privilegeModifyImageData) { %>
                            <a class="delete-series"
                                onclick="togglePopupDeleteSeries('${series.orthancSeriesUID}', '${studyId}', '${patient.id}')"><i class="icon-remove" delete-action></i>
                            </a>
                        <% } %>
                        <% if (ui.format(series.modality) != "RTDOSE"  && ui.format(series.modality) != "RTSTRUCT") { %>
                            <a href="${normalizedBaseUrl}/stone-webviewer/index.html?study=${studyInstanceUID}&series=${series.seriesInstanceUID}"
                                title="${ ui.message("imaging.app.openStoneView.label") }">
                                <img class="series-stone-img" src="${ ui.resourceLink("imaging", "images/stoneViewer.png") }"/></a>
                        <% } %>
                        <% if (ohifBaseUrl?.trim()) {
                            def normalizedOhifBaseUrl = ohifBaseUrl.endsWith("/") ? ohifBaseUrl[0..-2] : ohifBaseUrl
                        %>
                            <% /* OHIF's launch URL addresses a whole study, not a single series -
                                  there's no series-scoped deep link the way Stone Viewer has one,
                                  so this opens the same study-level viewer as the studies page. */ %>
                            <a href="${normalizedOhifBaseUrl}/viewer?StudyInstanceUIDs=${studyInstanceUID}" title="${ ui.message("imaging.app.openOHIFView.label") }" target="_blank">
                                <img class="ohif-img" alt="Show image in OHIF viewer" src="${ ui.resourceLink("imaging", "images/ohifViewer.png")}"/></a>
                        <% } %>
                        <% /* OHIF-AI: a second, independent viewer. Hidden unless the URL is set AND
                              the user holds the privilege - its output reaches the PACS. */ %>
                        <% if (privilegeUseOhifAi && ohifAiBaseUrl?.trim()) {
                            def normalizedOhifAiBaseUrl = ohifAiBaseUrl.endsWith("/") ? ohifAiBaseUrl[0..-2] : ohifAiBaseUrl
                        %>
                        <a href="${normalizedOhifAiBaseUrl}/viewer?StudyInstanceUIDs=${studyInstanceUID}" title="${ ui.message("imaging.app.openOhifAiView.label") }" target="_blank">
                                <img class="ohif-ai-img" alt="Show image in the AI viewer" src="${ ui.resourceLink("imaging", "images/ohifAiViewer.png")}"/></a>
                        <% } %>
                        <a href="${normalizedBaseUrl}/ui/app/#/filtered-studies?StudyInstanceUID=${studyInstanceUID}&expand=series" title="${ ui.message("imaging.app.orthancExplorer.label") }">
                            <img class="orthanc-img" alt="Show image data in Orthanc explorer" src="${ ui.resourceLink("imaging", "images/orthanc.png")}"/></a>
                        <% if (medreportAvailable) { %>
                            <a title="${ ui.message("imaging.app.writeReport.label") }"
                               href="${ ui.pageLink('medreport', 'imagingReports',
                                       [patientId : patient.id,
                                        studyUid  : orthancStudyUID,
                                        seriesUid : series.orthancSeriesUID,
                                        'new'     : '1']) }">
                                <i class="icon-file-alt"></i></a>
                        <% } %>
                     </td>
                </tr>
            <% } %>
        </tbody>
    </table>
</div>
<br/>

<div id="popupOverlayDeleteSeries" class="overlay-container">
    <div class="popup-box" style="width: 65%;">
        <h2>Delete Series</h2>
        <form name="deleteSeriesForm" class="form-container" method='POST'>
            <h3 id="deleteSeriesMessage">${ ui.message("imaging.deleteSeries.message") }</h3>
            <div class="popup-box-btn">
                <button class="btn-submit" type="submit">${ ui.message("imaging.action.delete") }</button>
                <button class="btn-close-popup" type="button" onclick="togglePopupDeleteSeries()">Cancel</button>
            </div>
        </form>
    </div>
</div>