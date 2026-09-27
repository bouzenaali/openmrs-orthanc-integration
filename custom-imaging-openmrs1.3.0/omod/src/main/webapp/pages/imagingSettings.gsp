<% ui.decorateWith("appui", "standardEmrPage", [ title: ui.message("imaging.app.orthancconfiguration.title") ])
   ui.includeCss("imaging", "general.css");
   ui.includeCss("imaging", "orthancConfiguration.css");
%>

<% ui.includeJavascript("imaging", "sortable.min.js") %>
<% ui.includeJavascript("imaging", "filter_table.js")%>

<h2><img class="orthanc-icon" src="${ ui.resourceLink("imaging", "images/orthanc.png") }"/> ${ ui.message("imaging.app.orthancconfiguration.heading")}</h2>
<br/>
<script>
    function togglePopupAdd() {
        const overlay = document.getElementById('popupOverlayAdd');
        overlay.classList.toggle('show');
    }

    function checkConfiguration() {
        fetch("/${contextPath}/module/imaging/checkConfiguration.form?url="+encodeURI(url.value)+"&proxyurl="+encodeURI(proxyurl.value)+"&username="+username.value+"&password="+password.value)
        .then((response)=> response.text())
        .then((text)=>window.alert(text))
    }

    function togglePopupDeleteOrthancConfiguration(id) {
        const overlay = document.getElementById('popupOverlayDeleteOrthancConfiguration');
        overlay.classList.toggle('show');
        document.deleteOrthancConfigurationForm.action = "/${contextPath}/module/imaging/deleteConfiguration.form?id="+id;
    }

    // Values are read from data- attributes rather than passed as onclick arguments,
        // so a quote in a URL cannot break the handler.
    function togglePopupEditOrthancConfiguration(el) {
        const overlay = document.getElementById('popupOverlayEditOrthancConfiguration');
        if (el) {
            document.getElementById('edit_id').value = el.dataset.id;
            document.getElementById('edit_url').value = el.dataset.url || "";
            document.getElementById('edit_proxyurl').value = el.dataset.proxyurl || "";
            document.getElementById('edit_username').value = el.dataset.username || "";
            document.getElementById('edit_password').value = "";
        }
        overlay.classList.toggle('show');
    }

    // The stored password is never sent to the browser, so a check can only be made
        // against one typed here.
    function checkEditConfiguration() {
        const p = document.getElementById('edit_password').value;
        if (!p) { window.alert("Enter the password to test the connection."); return; }
        fetch("/${contextPath}/module/imaging/checkConfiguration.form?url="
            +encodeURI(document.getElementById('edit_url').value)
            +"&proxyurl="+encodeURI(document.getElementById('edit_proxyurl').value)
            +"&username="+document.getElementById('edit_username').value
            +"&password="+p)
        .then((response)=> response.text())
        .then((text)=>window.alert(text))
    }

</script>
<div style="color:red;">
${pageMessage ?: ""}
</div>
 <% if (privilegeManagerOrthancConfiguration) { %>
    <div>
            <button class="btn-open-popup" onclick="togglePopupAdd()">Add new configuration</button>
    </div>
<% } %>
<div id="table-scroll">
    <table id="imaging-settings" class="table table-sm table-responsive-sm table-responsive-md table-responsive-lg table-responsive-xl" data-sortable>
        <thead class="imaging-table-thead">
           <script src="filter_table.js" defer></script>
           <tr>
                <th>${ ui.message("imaging.app.id.label")}</th>
                <th>${ ui.message("imaging.app.url.label")}</th>
                <th>${ ui.message("imaging.app.proxyUrl.label")}</th>
                <th>${ ui.message("imaging.app.username.label")}</th>
                <th style="width: max-content;">${ ui.message("imaging.delete.action") }</th>
            </tr>
        </thead>
        <tbody>
            <% if (orthancConfigurations.size() == 0) { %>
                <tr>
                    <td colspan="6" class="configure_td">${ui.message("imaging.app.none")}</td>
                </tr>
            <% } %>
            <% orthancConfigurations.each { orthancConfiguration -> %>
                <tr>
                    <td>${ui.format(orthancConfiguration.id)}</td>
                    <td>${ui.format(orthancConfiguration.orthancBaseUrl)}</td>
                    <td>${ui.format(orthancConfiguration.orthancProxyUrl)}</td>
                    <td>${ui.format(orthancConfiguration.orthancUsername)}</td>
                    <td>
                       <% if (privilegeManagerOrthancConfiguration) { %>
                            <a class="edit-configuration"
                               data-id="${orthancConfiguration.id}"
                               data-url="${orthancConfiguration.orthancBaseUrl ?: ''}"
                               data-proxyurl="${orthancConfiguration.orthancProxyUrl ?: ''}"
                               data-username="${orthancConfiguration.orthancUsername ?: ''}"
                               onclick="togglePopupEditOrthancConfiguration(this)">
                               <img class="edit-img" alt="${ ui.message("imaging.editOrthancConfiguration.label") }" title="${ ui.message("imaging.editOrthancConfiguration.label") }" src="${ ui.resourceLink("imaging", "images/edit.png")}"/></a>
                            <a class="delete-configuration"
                               onclick="togglePopupDeleteOrthancConfiguration('${orthancConfiguration.id}')">
                               <img class="delete-img" alt="Show the procedure step" src="${ ui.resourceLink("imaging", "images/delete.png")}"/></a>
                            </a>
                       <% } %>
                    </td>
                </tr>
            <% } %>
        </tbody>
    </table>
</div>
<div id="popupOverlayAdd" class="overlay-container">
    <div class="popup-box">
        <h2 style="color: #009384;">Add Orthanc configuration</h2>
        <form class="form-container" action="/${contextPath}/module/imaging/storeConfiguration.form" method="post">
            <label class="form-label" for="url">${ ui.message("imaging.app.url.label")}</label>
            <input class="form-input" type="text" placeholder="Orthanc URL" id="url" name="url" required>

            <label class="form-label" for="proxyurl">${ ui.message("imaging.app.proxyUrl.label")}</label>
            <input class="form-input" type="text" placeholder="Orthanc Proxy URL" id="proxyurl" name="proxyurl">

            <label class="form-label" for="username">${ ui.message("imaging.app.username.label")}</label>
            <input class="form-input" type="text" placeholder="Orthanc user name" id="username" name="username" required>

            <label class="form-label" for="password">${ ui.message("imaging.app.password.label")}</label>
            <input class="form-input" type="password" placeholder="Orthanc password" id="password" name="password" required>
            <div style="display: flex;">
                <button class="btn-check" type="button" onclick="checkConfiguration()">Check connection</button>
                <button class="btn-submit" type="submit">Save</button>
                <button class="btn-close-popup" type="button" onclick="togglePopupAdd()">Cancel</button>
            </div>
        </form>
    </div>
</div>

<div id="popupOverlayDeleteOrthancConfiguration" class="overlay-container">
    <div class="popup-box" style="width: 65%;">
        <h2>Delete Orthanc Configuration</h2>
        <form name="deleteOrthancConfigurationForm" class="form-container" method='POST'>
            <h3 id="deleteOrthancConfigurationMessage">${ ui.message("imaging.deleteOrthancConfiguration.message") }</h3>
            <div class="popup-box-btn">
                <button class="btn-submit" type="submit">${ ui.message("imaging.action.delete") }</button>
                <button class="btn-close-popup" type="button" onclick="togglePopupDeleteOrthancConfiguration()">Cancel</button>
            </div>
        </form>
    </div>
</div>

<div id="popupOverlayEditOrthancConfiguration" class="overlay-container">
    <div class="popup-box">
        <h2 style="color: #009384;">${ ui.message("imaging.editOrthancConfiguration.label") }</h2>
        <form class="form-container" action="/${contextPath}/module/imaging/updateConfiguration.form" method="post">
            <input type="hidden" id="edit_id" name="id"/>

            <label class="form-label" for="edit_url">${ ui.message("imaging.app.url.label")}</label>
            <input class="form-input" type="text" id="edit_url" name="url" required>

            <label class="form-label" for="edit_proxyurl">${ ui.message("imaging.app.proxyUrl.label")}</label>
            <input class="form-input" type="text" id="edit_proxyurl" name="proxyurl">

            <label class="form-label" for="edit_username">${ ui.message("imaging.app.username.label")}</label>
            <input class="form-input" type="text" id="edit_username" name="username" required>

            <label class="form-label" for="edit_password">${ ui.message("imaging.app.password.label")}</label>
            <input class="form-input" type="password" id="edit_password" name="password"
                   placeholder="${ ui.message("imaging.editOrthancConfiguration.passwordHint") }">
            <div style="display: flex;">
                <button class="btn-check" type="button" onclick="checkEditConfiguration()">Check connection</button>
                <button class="btn-submit" type="submit">Save</button>
                <button class="btn-close-popup" type="button" onclick="togglePopupEditOrthancConfiguration()">Cancel</button>
            </div>
        </form>
    </div>
</div>
