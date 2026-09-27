document.addEventListener("DOMContentLoaded", function () {
//    const tables = document.querySelectorAll("table");
    const tables = document.querySelectorAll("table:not(.no-filter)");

    tables.forEach(table => {
        const thead = table.querySelector("thead");
        if (!thead) return;

        const headerRow = thead.rows[0];
        const tfoot = table.createTFoot();
        const filterRow = tfoot.insertRow();

        Array.from(headerRow.cells).forEach((cell, columnIndex) => {
            const filterCell = document.createElement("td");

            // Check if column has the 'data-no-filter' attribute
            if (cell.hasAttributes("data-no-filter")) {
                filterRow.appendChild(filterCell)
                return;
            }

            const input = document.createElement("input");
            input.setAttribute("type", "text");
            input.setAttribute("placeholder", "Filter...");
            input.style.width = "100%";
            input.addEventListener("keyup", function () {
                filterTable(table, columnIndex, this.value);
            });
            filterCell.appendChild(input);
            filterRow.appendChild(filterCell);
        });
        table.insertBefore(tfoot, table.querySelector("tbody"));
//        tfoot.appendChild(filterRow);
    });
});

function filterTable(table, columnIndex, query) {
    const tbody = table.querySelector("tbody");
    if (!tbody) return;

    const rows = tbody.querySelectorAll("tr");
    rows.forEach(row => {
        const cell = row.cells[columnIndex];
        if (cell) {
            const text = cell.textContent || cell.innerText;
            row.style.display = text.toLowerCase().includes(query.toLowerCase()) ? "" : "none";
        }
    });
}
