<%
    ui.decorateWith("appui", "standardEmrPage",  [ title: ui.message("imaging.app.imageStudies.title") ])
    ui.includeCss("imaging", "general.css")
%>

<script type="text/javascript">
    var breadcrumbs = [
        { icon: "icon-home", link: '/' + OPENMRS_CONTEXT_PATH + '/index.htm' },
        { label: "${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.familyName))) }, ${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.givenName))) }",
            link: '${ui.pageLink("coreapps", "clinicianfacing/patient", [patientId: patient.id])}'},
        { label: "${ ui.message("imaging.studies") }", link: '${ui.pageLink("imaging", "studies", [patientId: patient.id])}'},
        { label: "${ ui.message("imaging.study.synchronization") }" }
    ];
</script>

<% ui.includeJavascript("imaging", "sortable.min.js") %>
<% ui.includeJavascript("imaging", "filter_table.js")%>

${ ui.includeFragment("coreapps", "patientHeader", [ patient: patient ]) }
${ ui.includeFragment("uicommons", "infoAndErrorMessage")}

<h2>
    ${ ui.message("imaging.studies.all") }
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

<div id="table-scroll">
    <table id="sync-studies" class="table table-sm table-responsive-sm table-responsive-md table-responsive-lg table-responsive-xl" data-sortable>
        <thead class="imaging-table-thead">
            <script src="filter_table.js" defer></script>
            <tr>
                <th style="width: 3px;"></th>
                <th>Match</th>
                <th>${ ui.message("imaging.app.matchConfidence.label")}</th>
                <th>${ ui.message("imaging.app.studyInstanceUid.label")}</th>
                <th>${ ui.message("imaging.app.patientName.label")}</th>
                <th>${ ui.message("imaging.app.date.label")}</th>
                <th>${ ui.message("imaging.app.description.label")}</th>
                <th>${ ui.message("imaging.app.server.label")}</th>
                <th data-no-filter style="width: 85px;">${ ui.message("coreapps.actions") }</th>
            </tr>
        </thead>
        <tbody>
            <% if (studies.size() == 0) { %>
                <tr>
                    <td colspan="9" align="center">${ui.message("imaging.studies.none")}</td>
                </tr>
            <% } %>
            <% studies.each { study ->
                def baseUrl = study.orthancConfiguration.orthancProxyUrl?.trim() ? study.orthancConfiguration.orthancProxyUrl : study.orthancConfiguration.orthancBaseUrl
                def normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl[0..-2] : baseUrl
                def tier = matchTier[study.studyInstanceUID]
            %>
                <tr>
                    <td>
                    <form method='POST' action='/${contextPath}/module/imaging/assignStudy.form?patientId=${patient.id}&studyId=${study.id}'>
                        <input type="checkbox" name="isChecked"
                                ${study.mrsPatient!=null && study.mrsPatient.id+""==param["patientId"].getAt(0) ? "checked" : ""}
                                onChange="this.form.submit()"/>
                    </form>
                    </td>
                    <td>${match[study.studyInstanceUID]}%</td>
                    <td>
                        <span style="display:inline-block; padding:2px 8px; border-radius:4px; color:#fff; font-size:0.85em; white-space:nowrap; background-color:${tier.colorHex};">${ui.message(tier.messageKey)}</span>
                    </td>
                    <td class="uid-td">${ui.format(study.studyInstanceUID)}</td>
                    <td>${ui.format(study.patientName)}</td>
                    <td>${ui.format(study.studyDate)}</td>
                    <td>${ui.format(study.studyDescription)}</td>
                    <td>${ui.format(study.orthancConfiguration.orthancBaseUrl)}</td>
                     <td>
                        <a href="${normalizedBaseUrl}/stone-webviewer/index.html?study=${ui.format(study.studyInstanceUID)}" title="${ ui.message("imaging.app.openStoneView.label") }">
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
                    </td>
                </tr>
            <% } %>
        </tbody>
    </table>
</div>
<br/>


















