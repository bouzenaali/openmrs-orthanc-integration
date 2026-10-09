# adminui force-password-change fix (HTTP 500 "No adapter for handler")

Applied: 2026-09-01. Target: `openmrs-app` (openmrs/openmrs-reference-application-distro), OpenMRS 2.4.3.

## Symptom

Any user whose `user_property` table contains `forcePassword = true` (the "Force password
change" option when creating/editing a user) could not use OpenMRS at all. Every request —
not just login — returned:

```
HTTP Status 500 – Internal Server Error
javax.servlet.ServletException: No adapter for handler
  [org.openmrs.module.adminui.page.controller.myaccount.ChangePasswordPageController@...]:
  The DispatcherServlet configuration needs to include a HandlerAdapter that supports this handler
```

`admin` was unaffected only because it has no `forcePassword` property.

## Root cause

Three pieces interacting:

1. **legacyui** registers `forcePasswordChangeFilter` programmatically in
   `org.openmrs.web.WebComponentRegistrar`, with
   `changePasswordForm = /admin/users/changePassword.form`, mapped to `/*`.
   For any authenticated user with `forcePassword=true` the filter does a **server-side
   forward** to that URL (`ServletContext.getRequestDispatcher(...).forward(...)`) instead of
   passing the request down the chain.

2. **legacyui** also ships the correct handler for that URL:
   `org.openmrs.web.controller.user.ChangePasswordFormController`, an annotated
   `@Controller` (`@RequestMapping("/admin/users/changePassword.form")`) rendering
   `/module/legacyui/admin/users/changePasswordForm`. Being annotated, it is dispatched
   normally by `RequestMappingHandlerAdapter`.

3. **adminui 1.6.0** hijacked the same URL in its `webModuleApplicationContext.xml`:

   ```xml
   <bean id="adminiuiUrlMapping" class="org.springframework.web.servlet.handler.SimpleUrlHandlerMapping">
       <property name="order"><value>10</value></property>
       <property name="mappings">
           <props>
               <prop key="/admin/users/changePassword.form">changePasswordPageController</prop>
           </props>
       </property>
   </bean>
   ```

   `changePasswordPageController` is a **UI Framework page controller**, not a Spring MVC
   handler. Spring has no `HandlerAdapter` that can invoke one. Because this mapping sits at
   `order=10` it wins over legacyui's annotated mapping, so the forward target was guaranteed
   to fail with `No adapter for handler`.

## The fix

`adminui-1.6.0.omod` was repacked with the `adminiuiUrlMapping` bean removed (replaced by an
explanatory comment). Nothing else in the module was touched — 220 entries in, 220 out, only
`webModuleApplicationContext.xml` differs. The `changePasswordPageController` bean itself was
left in place, so adminui's own page
(`/openmrs/adminui/myaccount/changePassword.page`, reached from **My Account → Change
Password**) is unaffected.

With the mapping gone, `/admin/users/changePassword.form` falls through to legacyui's real
controller, which renders the legacy change-password form and, on successful submit, calls
`UserService.changePassword(...)` then `UserProperties.setSupposedToChangePassword(false)` and
`saveUser(...)` — so the flag clears itself and the user proceeds normally. No redirect loop.

adminui 1.6.0 contains exactly one page (`myaccount/changePassword.gsp`), so the blast radius
of removing that mapping is minimal.

## Files here

| File | Purpose |
|---|---|
| `adminui-1.6.0.omod` | **patched** module — this is the one that is deployed |
| `adminui-1.6.0.omod.original-backup` | untouched original from the distro image (md5 `1a6211dfbed461eb66be5fa9beaab7cd`) |
| `webModuleApplicationContext.patched.xml` | the patched config, for reference/diffing |
| `apply.sh` | re-installs the patched module and restarts the container |
| `verify.sh` | credential-free check that the bug is gone |

Patched omod md5: `7ae48f68d6fd071e066f5c87d6886222`.

## Where it is installed, and why that is durable

The patched module was installed to `/usr/local/tomcat/.OpenMRS/modules/adminui-1.6.0.omod`,
i.e. into the **named Docker volume** `openmrs-orthanc-integration_openmrs_data`, which
survives container recreation. `webapps/` does *not* survive, so patching the bundled copy
there would have been lost on the next `docker compose up --force-recreate`.

Observed on the 2026-09-01 restart: OpenMRS loaded the module from the volume and **deleted**
the unpatched bundled duplicate from
`webapps/openmrs/WEB-INF/bundledModules/adminui-1.6.0.omod` (bundled module count went
40 → 39). The volume copy is authoritative, and this same resolution replays on a fresh
container.

**Caveat:** if the distro image is ever upgraded to a base carrying **adminui > 1.6.0**, the
newer bundled version will win over this 1.6.0 patch and the bug returns if it is still
present upstream. After any distro upgrade, run `verify.sh`.

## Verification performed

Credential-free reproduction, since handler resolution happens before authentication:

```
before:  GET /openmrs/admin/users/changePassword.form  -> HTTP 500, "No adapter for handler"
after:   GET /openmrs/admin/users/changePassword.form  -> HTTP 200, legacyui change-password
                                                          form (fields oldPassword / password /
                                                          confirmPassword)
```

Also checked after the restart: `adminui.started=true`, adminui's own `.page` URL still
responds (302 to login when unauthenticated), and the only ERROR lines during startup are
pre-existing noise present on earlier startups (metadata-package install failures, DWR
`dwr.xml` parse warnings, the Imaging module logging "Started Imaging" at ERROR level).

## Related database change

The three accounts that were locked out had their `forcePassword` property cleared so they
could log in before this fix existed:

```sql
DELETE FROM user_property WHERE property='forcePassword' AND user_id IN (11,13,14);
-- ibnelhaythem, Zahra, Angela
```

Those flags are still cleared. Now that the handler is fixed, "Force password change" is safe
to use again; re-enable it per user with:

```sql
INSERT INTO user_property (user_id, property, property_value) VALUES (<user_id>,'forcePassword','true');
```
