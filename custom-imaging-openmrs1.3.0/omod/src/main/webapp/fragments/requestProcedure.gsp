<%
    ui.includeCss("imaging", "worklist.css")
%>

${ ui.includeFragment("uicommons", "infoAndErrorMessage")}

<div class="info-section" requestProceduresNo>
    <div class="info-header">
        <i class="icon-x-ray"></i>
        <h3>${ ui.message("imaging.worklist").toUpperCase() }</h3>
        <i id ="imaging-worklist" class="icon-pencil edit-action right" title="${ ui.message("coreapps.edit") }" onclick="location.href='${ui.pageLink("imaging", "requestProcedure", [patientId: patient.patient.id])}';"></i>
    </div>
    <div class="info-body">
         ${ ui.message("imaging.patient.requestProcedure.number")}: ${ui.format(requestProceduresNo)}
    </div>
</div>