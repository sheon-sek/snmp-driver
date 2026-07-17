package com.jw.ignitionsdkboilerplate.common;

public final class ModuleConstants {
    public static final String MODULE_ID = "com.jw.ignitionSdkBoilerplate";
    public static final String MODULE_NAME = "Ignition SDK Boilerplate";

    public static final String MOUNT_ALIAS = "ignition-sdk-boilerplate";
    public static final String API_PREFIX = "/api/v1";

    public static final String GATEWAY_NAV_CATEGORY_ID = "ignition-sdk-boilerplate";

    // public static final String MODULE_WEBAPP_GW_FILENAME = "ignitionSdkBoilerplate.js";
    // public static final String MODULE_WEBAPP_GW_COMPONENT_NAME = "IgnitionSdkBoilerplate";
    // public static final String MODULE_WEBAPP_GW_NAV_CAREGORY_ID = "ignition-sdk-boilerplate";
    // public static final String MODULE_WEBAPP_GW_NAV_PATH = "/ignition-sdk-boilerplate";
    // public static final String MODULE_WEBAPP_GW_NAV_PAGENAME = "Home";

    // public static final String MODULE_WEBAPP_P_FILENAME = "ignitionSdkBoilerplatePerspective.js";
    // public static final String MODULE_WEBAPP_P_COMPONENT_CATEGORY = "Ignition SDK Boilerplate";

    private ModuleConstants() {
    }

    public static String getPersistentRecordPrefix() {
        return MODULE_NAME.trim().replace(" ", "");
    }

    public final class WebPackages {
        public static final String PAGE_FILENAME = "page.js";
        public static final String PERSPECTIVE_FILENAME = "perspective.js";
        public static final String WEBAPP_FILENAME = "webapp.js";
        public static final String WIDGET_FILENAME = "widget.js";
    }
}
