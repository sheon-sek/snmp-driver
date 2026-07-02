package com.jw.ignitionsdkboilerplate.designer;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.jw.ignitionsdkboilerplate.common.component.ExampleComponent;

public class DesignerHook extends AbstractDesignerModuleHook {
    private DesignerComponentRegistry registry;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        PerspectiveDesignerInterface pdi = PerspectiveDesignerInterface.get(context);
        registry = pdi.getDesignerComponentRegistry();
        registry.registerComponent(ExampleComponent.DESCRIPTOR);
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.removeComponent(ExampleComponent.COMPONENT_ID);
            registry = null;
        }
    }
}
