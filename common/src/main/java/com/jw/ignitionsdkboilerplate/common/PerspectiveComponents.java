package com.jw.ignitionsdkboilerplate.common;

import java.util.Set;

import com.inductiveautomation.perspective.common.api.BrowserResource;

public final class PerspectiveComponents {
    public static final Set<BrowserResource> BROWSER_RESOURCES = Set.of(
        new BrowserResource(
            "ignition-sdk-boilerplate-perspective-js",
            String.format(
                "/res/%s/%s",
                ModuleConstants.MOUNT_ALIAS,
                ModuleConstants.MODULE_WEBAPP_P_FILENAME
            ),
            BrowserResource.ResourceType.JS
        )
    );

    private PerspectiveComponents() {
    }
}
