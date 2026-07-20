package com.jw.snmpdriver.common;

public final class ModuleConstants {
    public static final String MODULE_ID = "com.jw.snmpdriver";
    public static final String MODULE_NAME = "SNMP Driver";

    public static final String MOUNT_ALIAS = "snmp-driver";
    public static final String API_PREFIX = "/api/v1";

    public static final String GATEWAY_NAV_CATEGORY_ID = MOUNT_ALIAS;

    private ModuleConstants() {
    }

    public static final class WebPackages {
        public static final String PAGE_FILENAME = "page.js";

        public static final String LICENSE_PAGE_EXPORT_NAME = "SnmpDriverLicensePage";

        private WebPackages() {
        }
    }
}
