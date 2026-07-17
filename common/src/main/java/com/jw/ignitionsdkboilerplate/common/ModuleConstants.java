package com.jw.ignitionsdkboilerplate.common;

public final class ModuleConstants {
    public static final String MODULE_ID = "com.jw.ignitionsdkboilerplate";
    public static final String MODULE_NAME = "Ignition SDK Boilerplate";

    public static final String MOUNT_ALIAS = "ignition-sdk-boilerplate";
    public static final String API_PREFIX = "/api/v1";

    public static final String GATEWAY_NAV_CATEGORY_ID = "ignition-sdk-boilerplate";

    private ModuleConstants() {
    }

    public static final class WebPackages {
        public static final String PERSPECTIVE_FILENAME = "perspective.js";
        public static final String WEBAPP_FILENAME = "webapp.js";
        public static final String WIDGET_FILENAME = "widget.js";
        public static final String PAGE_FILENAME = "page.js";

        private WebPackages() {
        }
    }
}
