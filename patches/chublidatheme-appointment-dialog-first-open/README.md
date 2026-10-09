# "Afficher tous les types" dialog is mispositioned on its first open

Applied: 2026-09-08. Target: `openmrs-app`, chublidatheme 1.0.7 (`.OpenMRS/modules`).
Affects `appointmentschedulingui/scheduleProviders.page` (Gérer les tranches de
rendez-vous) and `scheduleAppointment` - both use the same widget.

## Symptom

On a freshly loaded page, the first click on "Afficher tous les types" opens the
Services dialog anchored at the middle of the window instead of centred: the
service list runs off the bottom of the screen and the "Fermer" button cannot be
reached. Closing it and clicking again shows it correctly, and every later open
is correct.

## Root cause

`selectMultipleAppointmentTypesController.js` opens the dialog synchronously
inside the `ng-click` handler:

```js
$scope.displayViewAll = function() {
    $scope.showAllAppointmentTypesModal = true;      // ng-show; applied by the NEXT digest
    deleteAppointmentTypeDialog = emr.setupConfirmationDialog({selector: '#allAppointmentTypesModal'});
    deleteAppointmentTypeDialog.show();              // measures the dialog NOW
};
```

The digest that removes Angular's `.ng-hide` runs only after the handler
returns, so simplemodal measures the dialog while `.ng-hide`
(`display: none !important`) is still on it. jQuery measures a hidden element by
temporarily swapping in an inline `display` - which loses to `!important` - so
`this.d.data.outerHeight(true)` comes back as 0. simplemodal then sizes
`#simplemodal-container` to 2px and centres *that*:
`top = viewportHeight/2 - 2/2`.

Measured in a local reproduction (real jquery 1.12.4 + simplemodal 1.4.4 +
angular + this stylesheet, 1227x810 viewport):

| | container top | container height | dialog height | "Fermer" reachable |
|---|---|---|---|---|
| first open (bug) | 404 px | 2 px | 686 px | no (y=1039) |
| after fix | 62 px | 686 px | 686 px | yes (y=697) |

Later opens are correct because by then `.ng-hide` is gone (the class is only
re-added when `showAllAppointmentTypesModal` goes back to false, which the
overlay/Esc close path never does), so jQuery's measurement swap works.

## Fix

Presentation only, in the theme's section 12 - the closed dialog is parked
off-screen instead of `display:none`, which keeps it measurable:

```css
#allAppointmentTypesModal.ng-hide {
  display: block !important;
  visibility: hidden;
  position: absolute;
  left: -9999px;
  top: 0;
  animation: none !important;
}
```

`visibility: hidden` keeps it out of the tab order and the accessibility tree;
`position: absolute` at `left: -9999px` adds no scrollable overflow (verified:
`scrollWidth == clientWidth`). `animation: none` while parked means removing
`.ng-hide` changes the animation name and so still triggers `chu-dialog-in` on
open (verified: `getAnimations()` reports it running).

Specificity beats Angular's own rule in both orders - `#id.ng-hide` (1,1,0) vs
`.ng-hide:not(.ng-hide-animate)` (0,2,0) - so injection order does not matter.

No upstream module is modified, matching the theme's charter.

## Files

- `chublidatheme-omod-1.0.7.omod` - patched theme, install this.
- `chublidatheme-omod-1.0.7.omod.original-backup` - pristine 1.0.7 (md5 `b8ce51e8f962cc3aee30eedc47506371`).
- `chu-theme.css.patched` - the stylesheet that is written to the live path.
- `apply.sh` - installs the omod and the live stylesheet, then verifies. **No restart.**
- `verify.sh` - anonymous GET of the served stylesheet; fails if the rule is absent.
- `rollback.sh` - restores the pristine theme, also without a restart.

## Caveats

- The stylesheet is served by openmrs-core's `ModuleResourcesServlet` from
  `WEB-INF/view/module/chublidatheme/resources/`, so the live copy is patched
  directly and the fix applies on the next page load. The omod is patched too
  because a restart re-expands the module from it.
- Only `Last-Modified` is sent for this asset (no `Cache-Control`/`ETag`), so a
  browser can serve its cached copy for a couple of hours. `Ctrl+Shift+R`, or
  bumping `chublidatheme.assetVersion` (Administration > Settings), forces a
  refetch.
- **This is a patched binary, not a theme release.** Fold the rule into the
  chublidatheme source (section 12 of `chu-theme.css`) before the next build,
  or 1.0.8 will lose it. The source is not present on this host.
- The underlying defect is upstream in appointmentschedulingui; the one-line
  proper fix is to wrap the `setupConfirmationDialog`/`show()` pair in
  `$timeout(...)` in `selectMultipleAppointmentTypesController.js`. That would
  mean patching a module bundled inside the WAR (lost whenever the container is
  recreated), which is why the fix lives in the theme instead.
