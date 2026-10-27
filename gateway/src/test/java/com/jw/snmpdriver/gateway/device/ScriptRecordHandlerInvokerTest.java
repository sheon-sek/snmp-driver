package com.jw.snmpdriver.gateway.device;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionManifest;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import org.junit.jupiter.api.Test;
import org.python.core.PySyntaxError;

class ScriptRecordHandlerInvokerTest {
    @Test
    void rejectsMissingDisabledAndInheritableProjectsBeforeObtainingScripts() {
        assertUnavailable(Map.of());
        assertUnavailable(Map.of("project", new ResourceCollectionManifest("", "", false, false, null)));
        assertUnavailable(Map.of("project", new ResourceCollectionManifest("", "", true, true, null)));
    }

    @Test
    void syntaxErrorDuringProjectScriptLookupIsNotFound() {
        org.python.core.PySystemState.initialize();
        ProjectManager projects = (ProjectManager) Proxy.newProxyInstance(ProjectManager.class.getClassLoader(),
                new Class<?>[]{ProjectManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getManifests")) {
                        return Map.of("project", new ResourceCollectionManifest("", "", true, false, null));
                    }
                    if (method.getName().equals("getProjectScriptManager")) {
                        throw new PySyntaxError("broken library", 1, 1, "bad syntax !", "handlers.py");
                    }
                    return null;
                });
        GatewayContext gateway = (GatewayContext) Proxy.newProxyInstance(GatewayContext.class.getClassLoader(),
                new Class<?>[]{GatewayContext.class}, (proxy, method, args) ->
                        method.getName().equals("getProjectManager") ? projects : null);
        assertEquals(RecordHandlerInvoker.Outcome.NOT_FOUND,
                new ScriptRecordHandlerInvoker(gateway).invoke("project", "snmp.handlers.onNotification", "{}")
                        .outcome());
    }

    private void assertUnavailable(Map<String, ResourceCollectionManifest> manifests) {
        AtomicInteger lookups = new AtomicInteger();
        ProjectManager projects = (ProjectManager) Proxy.newProxyInstance(ProjectManager.class.getClassLoader(),
                new Class<?>[]{ProjectManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getManifests")) {
                        return manifests;
                    }
                    if (method.getName().equals("getProjectScriptManager")) {
                        lookups.incrementAndGet();
                    }
                    return null;
                });
        GatewayContext gateway = (GatewayContext) Proxy.newProxyInstance(GatewayContext.class.getClassLoader(),
                new Class<?>[]{GatewayContext.class}, (proxy, method, args) ->
                        method.getName().equals("getProjectManager") ? projects : null);
        var result = new ScriptRecordHandlerInvoker(gateway).invoke("project", "snmp.handlers.onNotification", "{}");
        assertEquals(RecordHandlerInvoker.Outcome.NOT_FOUND, result.outcome());
        assertEquals(0, lookups.get());
    }
}
