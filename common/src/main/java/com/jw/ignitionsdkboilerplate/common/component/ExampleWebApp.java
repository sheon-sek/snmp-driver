package com.jw.ignitionsdkboilerplate.common.component;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;
import com.jw.ignitionsdkboilerplate.common.ModuleConstants;
import com.jw.ignitionsdkboilerplate.common.PerspectiveComponents;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class ExampleWebApp {
    // Equivalent to COMPONENT_TYPE in React ComponentMeta's getComponentType
    public static final String COMPONENT_ID = "ignitionboilerplate.example-webapp";
    public static final String COMPONENT_NAME = "Example Embedded WebApp";

    public static final JsonSchema SCHEMA = loadSchema();

    public static final ComponentDescriptor DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory(PerspectiveComponents.COMPONENT_CATEGORY)
        .setId(COMPONENT_ID)
        .setModuleId(ModuleConstants.MODULE_ID)
        .setSchema(SCHEMA)
        .setName(COMPONENT_NAME)
        .addPaletteEntry(
            "",
            COMPONENT_NAME,
            "An example of embedded customized web app.",
            null,
            null
        )
        .setDefaultMetaName("exampleWebapp")
        .setResources(PerspectiveComponents.BROWSER_RESOURCES)
        .build();

    private ExampleWebApp() {
    }

    private static JsonSchema loadSchema() {
        try (InputStream stream = Objects.requireNonNull(
                ExampleWebApp.class.getResourceAsStream("/example-webapp.props.json"),
                "Missing Perspective component schema: /example-webapp.props.json"
        )) {
            return JsonSchema.parse(stream);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
