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

<div style="color:red;">
${param["message"]?.getAt(0) ?: ""}
</div>

<div id="table-scroll">
    <table id="sync-studies" class="table table-sm table-responsive-sm table-responsive-md table-responsive-lg table-responsive-xl" data-sortable>
        <thead class="imaging-table-thead">
            <script src="filter_table.js" defer></script>
            <tr>
                <th style="width: 3px;"></th>
                <th>Match</th>
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
                    <td colspan="6" align="center">${ui.message("imaging.studies.none")}</td>
                </tr>
            <% } %>
            <% studies.each { study ->
                def baseUrl = study.orthancConfiguration.orthancProxyUrl?.trim() ? study.orthancConfiguration.orthancProxyUrl : study.orthancConfiguration.orthancBaseUrl
                def normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl[0..-2] : baseUrl
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
                    <td class="uid-td">${ui.format(study.studyInstanceUID)}</td>
                    <td>${ui.format(study.patientName)}</td>
                    <td>${ui.format(study.studyDate)}</td>
                    <td>${ui.format(study.studyDescription)}</td>
                    <td>${ui.format(study.orthancConfiguration.orthancBaseUrl)}</td>
                     <td>
                        <a href="${normalizedBaseUrl}/stone-webviewer/index.html?study=${ui.format(study.studyInstanceUID)}" title="${ ui.message("imaging.app.openStoneView.label") }">
                            <img class="stone-img" alt="Show image in stone viewer" src="${ ui.resourceLink("imaging", "images/stoneViewer.png")}"/></a>
                        <a href="${normalizedBaseUrl}/ui/app/#/filtered-studies?StudyInstanceUID=${ui.format(study.studyInstanceUID)}&expand=study" title="${ ui.message("imaging.app.orthancExplorer.label") }">
                            <img class="orthanc-img" alt="Show image data in Orthanc explorer" src="${ ui.resourceLink("imaging", "images/orthanc.png")}"/></a>
                    </td>
                </tr>
            <% } %>
        </tbody>
    </table>
</div>
<br/>


















