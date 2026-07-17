package com.jw.ignitionsdkboilerplate.common;

import java.util.Set;

import com.inductiveautomation.perspective.common.api.BrowserResource;

public final class PerspectiveComponents {
    public static final String COMPONENT_CATEGORY = ModuleConstants.MODULE_NAME;
    public static final Set<BrowserResource> BROWSER_RESOURCES = Set.of(
        new BrowserResource(
            String.format("%s.perspective.js", ModuleConstants.MODULE_ID),
            String.format(
                "/res/%s/%s",
                ModuleConstants.MOUNT_ALIAS,
                ModuleConstants.WebPackages.PERSPECTIVE_FILENAME
            ),
            BrowserResource.ResourceType.JS
        )
    );

    private PerspectiveComponents() {
    }
}
