package com.jw.ignitionsdkboilerplate.common.component;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;
import com.jw.ignitionsdkboilerplate.common.PerspectiveComponents;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;

public final class ExampleComponent {
    public static final String COMPONENT_ID = "com.jw.ignitionsdkboilerplate.example";
    public static final String COMPONENT_NAME = "Ignition SDK Boilerplate Example";

    public static final JsonSchema SCHEMA =
        JsonSchema.parse(ExampleComponent.class.getResourceAsStream("/example-component.props.json"));

    public static final ComponentDescriptor DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory(ModuleConstants.MODULE_WEBAPP_P_COMPONENT_CATEGORY)
        .setId(COMPONENT_ID)
        .setModuleId(ModuleConstants.MODULE_ID)
        .setSchema(SCHEMA)
        .setName(COMPONENT_NAME)
        .addPaletteEntry(
            "",
            COMPONENT_NAME,
            "Displays the Gateway health status.",
            null,
            null
        )
        .setDefaultMetaName("ignitionSdkBoilerplateExample")
        .setResources(PerspectiveComponents.BROWSER_RESOURCES)
        .build();

    private ExampleComponent() {
    }
}
