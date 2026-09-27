<%
    ui.decorateWith("appui", "standardEmrPage",  [ title: ui.message("imaging.app.instances.title") ])
    ui.includeCss("imaging", "general.css")
    ui.includeCss("imaging", "instances.css")
%>

<script type="text/javascript">
    var breadcrumbs = [
        { icon: "icon-home", link: '/' + OPENMRS_CONTEXT_PATH + '/index.htm' },
        { label: "${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.familyName))) }, ${ ui.escapeJs(ui.encodeHtmlContent(ui.format(patient.givenName))) }",
            link: '${ui.pageLink("coreapps", "clinicianfacing/patient", [patientId: patient.id])}'},
        { label: "${ ui.message("imaging.studies") }",
            link: '${ui.pageLink("imaging", "studies", [patientId: patient.id])}'},
        { label: "${ ui.message("imaging.series") }",
            link: '${ui.pageLink("imaging", "series", [patientId: patient.id, studyId: param["studyId"].getAt(0)])}'},
        { label: "${ ui.message("imaging.instances") }" }
    ];
</script>

${ ui.includeFragment("coreapps", "patientHeader", [ patient: patient ]) }
${ ui.includeFragment("uicommons", "infoAndErrorMessage")}
<% ui.includeJavascript("imaging", "sortable.min.js") %>
<% ui.includeJavascript("imaging", "filter_table.js")%>

<h2>
    ${ ui.message("imaging.instances") }
</h2>

<script>
    function togglePopupPreview(orthancInstanceUID, studyId) {
        const overlay = document.getElementById('popupOverlayPreview');
        overlay.classList.toggle('show');

        if(orthancInstanceUID) {
            const container = document.getElementById("preview-container");
            if(container.lastChild) container.removeChild(container.lastChild);
            container.appendChild(document.createTextNode("Loading preview"))

            const url = '/${contextPath}/module/imaging/previewInstance.form?orthancInstanceUID='+orthancInstanceUID+'&studyId='+studyId
            fetch(url, { method: 'GET'})
                .then((response) => {
                    if(response.ok)
                        return response.blob()
                    else
                        return Promise.reject(response)
                })
                .then((blob) => {
                    const imageUrl = URL.createObjectURL(blob);
                    const imageElement = document.createElement("img");
                    imageElement.src = imageUrl;
                    imageElement.width = 340;
                    imageElement.height = 300;
                    container.removeChild(container.lastChild);
                    container.appendChild(imageElement);
                })
                .catch((response) => {
                    container.removeChild(container.lastChild);
                    return response.text()
                })
                .then((error) => {
                    if(error) container.appendChild(document.createTextNode(error));
                })
                .catch((error) => {
                    container.appendChild(document.createTextNode("Preview failed with status code "+response.status));
                })
          }
    }
</script>

<div id="table-scroll">
    <table id="instances" class="table table-sm table-responsive-sm table-responsive-md table-responsive-lg table-responsive-xl" data-sortable>
        <thead class="imaging-table-thead">
            <script src="filter_table.js" defer></script>
            <tr>
                <th>${ ui.message("imaging.app.sopInstanceUID.label")}</th>
                <th>${ ui.message("imaging.app.instanceNumber.label")}</th>
                <th>${ ui.message("imaging.app.imagePositionPatient.label")}</th>
                <th>${ ui.message("imaging.app.numberOfFrames.label")}</th>
                <th data-no-filter style="width: 150px;">${ ui.message("coreapps.actions") }</th>
            </tr>
        </thead>
        <tbody>
             <% if (instances.size() == 0) { %>
                <tr>
                    <td colspan="6" align="center">${ui.message("imaging.instances.none")}</td>
                </tr>
            <% } %>
            <% instances.each { instance ->
                def baseUrl = instance.orthancConfiguration.orthancProxyUrl?.trim() ? instance.orthancConfiguration.orthancProxyUrl : instance.orthancConfiguration.orthancBaseUrl
                def normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl[0..-2] : baseUrl
            %>
                <tr>
                    <td class="uid-td">${ui.format(instance.sopInstanceUID)}</td>
                    <td>${ui.format(instance.instanceNumber)}</td>
                    <td>${ui.format(instance.imagePositionPatient)}</td>
                    <td>${ui.format(instance.numberOfFrames)}</td>
                    <td>
                        <% if (ui.format(instance.numberOfFrames) == "") { %>
                            <a title="${ ui.message("imaging.app.instancePreview.label") }"
                                onclick="togglePopupPreview('${instance.orthancInstanceUID}', '${param['studyId'].getAt(0)}')">
                                <img class="instance-preview" src="${ ui.resourceLink("imaging", "images/preview.png") }"/>
                            </a>
                            <a href="${normalizedBaseUrl}/instances/${ui.format(instance.orthancInstanceUID)}/preview" title="${ ui.message("imaging.app.instancePreview.label") }">
                                <img class="instance-preview" src="${ ui.resourceLink("imaging", "images/preview.png") }"/>
                            </a>
                        <% } %>
                        <a href="${normalizedBaseUrl}/ui/app/#/filtered-studies?StudyInstanceUID=${studyInstanceUID}&expand=series" title="${ ui.message("imaging.app.orthancExplorer.label") }">
                            <img class="orthanc-img" src="${ ui.resourceLink("imaging", "images/orthanc.png")}"/></a>
                    </td>
                </tr>
            <% } %>
        </tbody>
    </table>
</div>
<br/>

<div id="popupOverlayPreview" class="overlay-container">
    <div class="popup-box" style="width: 55%;">
        <h2>Instance Preview</h2>
        <div id="preview-container"></div>
        <div class="popup-box-btn">
            <button class="btn-close-popup" style="margin-top: 20px;" type="button" onclick="togglePopupPreview()">Close</button>
        </div>
    </div>
</div>

