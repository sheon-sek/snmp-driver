package com.jw.ignitionsdkboilerplate.designer;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.jw.ignitionsdkboilerplate.common.component.ExampleWebApp;

public class DesignerHook extends AbstractDesignerModuleHook {
    private static final LoggerEx logger = LoggerEx.newBuilder().build("IgnitionBoilerplateComponents");

    private DesignerComponentRegistry registry;

    static {
        BundleUtil.get().addBundle("components", DesignerHook.class.getClassLoader(), "components");
    }

    public DesignerHook() {
        logger.info("Registering components in Designer...");
    }

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        logger.debug("Initializing registry entrants...");
        PerspectiveDesignerInterface pdi = PerspectiveDesignerInterface.get(context);

        registry = pdi.getDesignerComponentRegistry();

        // register components to get them on the palette
        registry.registerComponent(ExampleWebApp.DESCRIPTOR);
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.removeComponent(ExampleWebApp.COMPONENT_ID);

            registry = null;
        }
    }
}
