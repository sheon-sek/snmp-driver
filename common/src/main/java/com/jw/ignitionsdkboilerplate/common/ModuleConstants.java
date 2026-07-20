package com.jw.ignitionsdkboilerplate.common;

public final class ModuleConstants {
    public static final String MODULE_ID = "com.jw.ignitionsdkboilerplate";
    public static final String MODULE_NAME = "Ignition SDK Boilerplate";

    public static final String MOUNT_ALIAS = "ignition-sdk-boilerplate";
    public static final String API_PREFIX = "/api/v1";

    public static final String GATEWAY_NAV_CATEGORY_ID = MOUNT_ALIAS;

    private ModuleConstants() {
    }

    public static final class WebPackages {
        public static final String PERSPECTIVE_FILENAME = "perspective.js";
        public static final String WEBAPP_FILENAME = "webapp.js";
        public static final String WIDGET_FILENAME = "widget.js";
        public static final String PAGE_FILENAME = "page.js";

        public static final String WEBAPP_EXPORT_NAME = "IgnitionSdkBoilerplateWebApp";
        public static final String COUNTER_PAGE_EXPORT_NAME = "IgnitionSdkBoilerplateCounterPage";
        public static final String LICENSE_PAGE_EXPORT_NAME = "IgnitionSdkBoilerplateLicensePage";

        private WebPackages() {
        }
    }
}
