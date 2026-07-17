package com.jw.ignition.auth.persistence;

import com.inductiveautomation.ignition.gateway.localdb.persistence.DateField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IdentityField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IntField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.localdb.persistence.StringField;
import com.jw.ignition.auth.AuthConstants;
import simpleorm.dataset.SFieldFlags;

import java.util.Date;

public class AppAuthConfigRecord extends PersistentRecord {
    public static final String TABLENAME = AuthConstants.PERSISTENCE_PREFIX + "_AppAuthConfig";
    public static final RecordMeta<AppAuthConfigRecord> META = new RecordMeta<>(AppAuthConfigRecord.class, TABLENAME);

    public static final IdentityField Id = new IdentityField(META);
    public static final StringField Namespace = new StringField(META, "Namespace", SFieldFlags.SMANDATORY);
    public static final StringField ConfigJson = new StringField(META, "ConfigJson", SFieldFlags.SMANDATORY);
    public static final IntField Revision = new IntField(META, "Revision").setDefault(0);
    public static final StringField UpdatedBy = new StringField(META, "UpdatedBy").setDefault("system");
    public static final DateField UpdatedAt = new DateField(META, "UpdatedAt");

    static {
        META.setNounKey(TABLENAME + ".Noun");
        META.setNounPluralKey(TABLENAME + ".Noun.Plural");
    }

    @Override public RecordMeta<?> getMeta() { return META; }

    public String getNamespace() { return getString(Namespace); }
    public void setNamespace(String namespace) { setString(Namespace, namespace); }
    public String getConfigJson() { return getString(ConfigJson); }
    public void setConfigJson(String configJson) { setString(ConfigJson, configJson); }
    public int getRevision() { Integer value = getIntObj(Revision); return value == null ? 0 : value; }
    public void setRevision(int revision) { setInt(Revision, revision); }
    public String getUpdatedBy() { return getString(UpdatedBy); }
    public void setUpdatedBy(String value) { setString(UpdatedBy, value == null ? "unknown" : value); }
    public Date getUpdatedAt() { return getDate(UpdatedAt); }
    public void setUpdatedAt(Date value) { setDate(UpdatedAt, value == null ? new Date() : value); }
}
