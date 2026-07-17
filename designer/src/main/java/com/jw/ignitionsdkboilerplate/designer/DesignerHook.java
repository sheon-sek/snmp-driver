package com.jw.ignitionsdkboilerplate.designer;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;
import com.jw.ignitionsdkboilerplate.common.component.ExampleWebApp;

public class DesignerHook extends AbstractDesignerModuleHook {
    private static final LoggerEx logger = LoggerEx.newBuilder()
            .build(ModuleConstants.MODULE_ID + ".DesignerComponents");

    private DesignerComponentRegistry registry;

    static {
        BundleUtil.get().addBundle("components", DesignerHook.class.getClassLoader(), "components");
    }

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        logger.info("Registering components in Designer...");
        PerspectiveDesignerInterface pdi = PerspectiveDesignerInterface.get(context);
        DesignerComponentRegistry currentRegistry = pdi.getDesignerComponentRegistry();

        currentRegistry.registerComponent(ExampleWebApp.DESCRIPTOR);
        registry = currentRegistry;
    }

    @Override
    public void shutdown() {
        DesignerComponentRegistry currentRegistry = registry;

        if (currentRegistry != null) {
            currentRegistry.removeComponent(ExampleWebApp.COMPONENT_ID);
            registry = null;
        }
    }
}
