package org.openmrs.module.medreport.api.model;

import org.openmrs.User;

import java.util.Date;

/**
 * A user's saved report-personalisation choices, so the second and every later report does
 * not have to be configured from scratch.
 *
 * <p>The value is an opaque JSON blob owned by the personalisation form rather than a column
 * per option. Selections are a tree of section and field ids that grows every time a
 * contributing module declares new data, so a fixed column set would need a migration for
 * every contribution - and preferences are only ever read back whole, by the same form that
 * wrote them.
 *
 * <p>Preferences are per user, never shared: they are stored against {@code user}, and the
 * service only ever reads or writes the authenticated user's own row. They are also only a
 * <em>convenience</em>: what a preference asks for is re-filtered against live privileges at
 * generation time, so a stale preference can never widen access after a role change.
 */
public class UserReportPreference {

    private Integer id;

    private String uuid;

    private User user;

    private String preferenceKey;

    private String preferenceValue;

    private Date dateCreated;

    private Date dateChanged;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getPreferenceKey() {
        return preferenceKey;
    }

    public void setPreferenceKey(String preferenceKey) {
        this.preferenceKey = preferenceKey;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public void setPreferenceValue(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    public Date getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Date dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Date getDateChanged() {
        return dateChanged;
    }

    public void setDateChanged(Date dateChanged) {
        this.dateChanged = dateChanged;
    }
}
