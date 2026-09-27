<%
    ui.includeCss("imaging", "studies.css")
%>

${ ui.includeFragment("uicommons", "infoAndErrorMessage")}

<div class="info-section patientStudiesNo">
    <div class="info-header">
        <i class="icon-x-ray"></i>
        <h3>${ ui.message("imaging.imaging").toUpperCase() }</h3>
        <i id ="imaging-editStudies" class="icon-pencil edit-action right" title="${ ui.message("coreapps.edit") }" onclick="location.href='${ui.pageLink("imaging", "studies", [patientId: patient.patient.id])}';"></i>
    </div>
    <div class="info-body">
         ${ ui.message("imaging.patient.studies.number")}: ${ui.format(patientStudiesNo)}
    </div>
</div>