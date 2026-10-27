package com.jw.snmpdriver.gateway.device;

import java.util.Objects;

import com.inductiveautomation.ignition.common.script.JythonExecException;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.python.core.Py;
import org.python.core.PyObject;
import org.python.core.PyStringMap;

/**
 * Runs the handler through the project's Gateway {@link ScriptManager}. Nothing is cached: the ScriptManager and
 * the function are looked up again for every Record, so a saved Script Library applies to the next one.
 */
final class ScriptRecordHandlerInvoker implements RecordHandlerInvoker {
    private static final String HANDLER_VARIABLE = "__snmp_record_handler__";
    private static final String SCRIPT_NAME = "<snmp-driver record handler>";

    private final GatewayContext gateway;

    ScriptRecordHandlerInvoker(GatewayContext gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }

    @Override
    public Result invoke(String project, String function, String record) {
        if (!SNMPDeviceConfig.isValidRecordHandlerFunction(function)) {
            return Result.notFound("Record Handler Function '" + function + "' is not a dotted function path");
        }
        ScriptManager scripts;
        try {
            var projects = gateway.getProjectManager();
            var manifest = projects.getManifests().get(project);
            if (manifest == null || !manifest.enabled() || manifest.inheritable()) {
                return Result.notFound("Project '" + project + "' was not found, is disabled or is inheritable");
            }
            scripts = projects.getProjectScriptManager(project);
        } catch (RuntimeException error) {
            return Result.notFound("Project '" + project + "' is unavailable: " + error);
        }
        if (scripts == null) {
            return Result.notFound("Project '" + project + "' was not found, is disabled or cannot run scripts");
        }

        PyObject handler;
        try {
            // The path is validated as dotted identifiers. Resolution errors, including PySyntaxError
            // from loading a broken Script Library, mean the function is unavailable (NOT_FOUND).
            PyStringMap locals = scripts.createLocalsMap();
            scripts.runCode(HANDLER_VARIABLE + " = " + function, locals, SCRIPT_NAME);
            handler = locals.__finditem__(HANDLER_VARIABLE);
        } catch (JythonExecException | RuntimeException error) {
            return Result.notFound("Function '" + function + "' was not found in project '" + project + "': "
                    + summary(error));
        }
        if (handler == null || !handler.isCallable()) {
            return Result.notFound("'" + function + "' in project '" + project + "' is not a function");
        }

        try {
            scripts.runFunction(handler, Py.newUnicode(record));
            return Result.SUCCESS;
        } catch (JythonExecException | RuntimeException error) {
            return Result.handlerFailed(summary(error));
        }
    }

    private static String summary(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.strip();
    }
}
