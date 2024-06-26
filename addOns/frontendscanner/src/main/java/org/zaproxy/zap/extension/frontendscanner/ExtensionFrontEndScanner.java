/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2018 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.frontendscanner;

import java.awt.EventQueue;
import java.awt.event.ItemEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.ImageIcon;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.Extension;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.zaproxy.zap.extension.alert.ExtensionAlert;
import org.zaproxy.zap.extension.script.ExtensionScript;
import org.zaproxy.zap.extension.script.ScriptType;
import org.zaproxy.zap.extension.script.ScriptWrapper;
import org.zaproxy.zap.view.ZapToggleButton;

/**
 * A ZAP extension which allow to run scripts in the browser to detect vulnerabilities in web
 * applications relying heavily on Javascript.
 */
public class ExtensionFrontEndScanner extends ExtensionAdaptor {

    // The name is public so that other extensions can access it
    public static final String NAME = "ExtensionFrontEndScanner";

    public static final String SCRIPT_TYPE_CLIENT_ACTIVE = "client-side-active";
    public static final String SCRIPT_TYPE_CLIENT_PASSIVE = "client-side-passive";

    protected static final String PREFIX = "frontendscanner";

    private static final String RESOURCE = "/org/zaproxy/zap/extension/frontendscanner/resources";
    private static final String SCRIPTS_FOLDER = Constant.getZapHome() + "/scripts/scripts/";

    private static final String ASCAN_ICON = RESOURCE + "/client-side-ascan.png";
    private static final String PSCAN_ICON = RESOURCE + "/client-side-pscan.png";

    private ZapToggleButton frontEndScannerButton = null;

    private ScriptType activeScriptType;
    private ScriptType passiveScriptType;
    private ExtensionScript extensionScript;

    private FrontEndScannerAPI api;
    private FrontEndScannerOptions options;
    private FrontEndScannerProxyListener proxyListener;

    private static final Logger LOGGER = LogManager.getLogger(ExtensionFrontEndScanner.class);

    private static final List<Class<? extends Extension>> DEPENDENCIES =
            List.of(ExtensionAlert.class, ExtensionScript.class);

    public ExtensionFrontEndScanner() {
        super(NAME);
    }

    @Override
    public void init() {
        super.init();

        this.options = new FrontEndScannerOptions();

        this.api = new FrontEndScannerAPI(this);
        this.api.addApiOptions(options);

        this.proxyListener = new FrontEndScannerProxyListener(api, options);

        this.extensionScript =
                Control.getSingleton().getExtensionLoader().getExtension(ExtensionScript.class);
    }

    @Override
    public void hook(ExtensionHook extensionHook) {
        super.hook(extensionHook);

        extensionHook.addProxyListener(this.proxyListener);

        extensionHook.addOptionsParamSet(options);
        extensionHook.addApiImplementor(api);

        if (hasView()) {
            extensionHook.getHookView().addMainToolBarComponent(getFrontEndScannerButton());
            options.addPropertyChangeListener(
                    "enabled",
                    e ->
                            EventQueue.invokeLater(
                                    () ->
                                            getFrontEndScannerButton()
                                                    .setSelected((boolean) e.getNewValue())));
        }

        activeScriptType =
                new ScriptType(
                        SCRIPT_TYPE_CLIENT_ACTIVE,
                        "frontendscanner.scripts.type.active",
                        createIcon(ASCAN_ICON),
                        true);

        passiveScriptType =
                new ScriptType(
                        SCRIPT_TYPE_CLIENT_PASSIVE,
                        "frontendscanner.scripts.type.passive",
                        createIcon(PSCAN_ICON),
                        true);

        this.extensionScript.registerScriptType(activeScriptType);
        this.extensionScript.registerScriptType(passiveScriptType);
    }

    @Override
    public void optionsLoaded() {
        if (hasView()) {
            EventQueue.invokeLater(
                    () -> getFrontEndScannerButton().setSelected(options.isEnabled()));
        }
    }

    @Override
    public void postInit() {
        super.postInit();

        registerUserScripts(activeScriptType);
        registerUserScripts(passiveScriptType);
    }

    @Override
    public boolean canUnload() {
        // The extension can be dynamically unloaded, all resources used/added can be freed/removed
        // from core.
        return true;
    }

    @Override
    public void unload() {
        super.unload();

        this.extensionScript.removeScriptType(activeScriptType);
        this.extensionScript.removeScriptType(passiveScriptType);
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString(PREFIX + ".desc");
    }

    @Override
    public List<Class<? extends Extension>> getDependencies() {
        return DEPENDENCIES;
    }

    protected ExtensionScript getExtensionScript() {
        return this.extensionScript;
    }

    private ZapToggleButton getFrontEndScannerButton() {
        if (this.frontEndScannerButton == null) {
            this.frontEndScannerButton = new ZapToggleButton(createIcon(PSCAN_ICON));
            this.frontEndScannerButton.setSelectedToolTipText(
                    Constant.messages.getString(PREFIX + ".toolbar.button.on.tooltip"));
            this.frontEndScannerButton.setToolTipText(
                    Constant.messages.getString(PREFIX + ".toolbar.button.off.tooltip"));
            this.frontEndScannerButton.addItemListener(
                    e -> options.setEnabled(ItemEvent.SELECTED == e.getStateChange()));
        }
        return this.frontEndScannerButton;
    }

    private ImageIcon createIcon(String path) {
        if (!hasView()) {
            return null;
        }
        return new ImageIcon(ExtensionFrontEndScanner.class.getResource(path));
    }

    private void registerUserScripts(ScriptType scriptType) {
        String folder = SCRIPTS_FOLDER + scriptType.getName() + '/';
        Path scriptFolderPath = Paths.get(folder);

        try (Stream<Path> scriptFilePaths = Files.list(scriptFolderPath)) {
            scriptFilePaths
                    .map(Path::toFile)
                    .filter(File::isFile)
                    .map(
                            file ->
                                    new ScriptWrapper(
                                            file.getName(), "", "Null", scriptType, true, file))
                    .map(this::loadScript)
                    .filter(Optional<ScriptWrapper>::isPresent)
                    .map(Optional<ScriptWrapper>::get)
                    // Keep scripts are not registered yet.
                    .filter(
                            scriptWrapper ->
                                    this.extensionScript.getScript(scriptWrapper.getName()) == null)
                    .forEach(scriptWrapper -> this.extensionScript.addScript(scriptWrapper, false));
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    private Optional<ScriptWrapper> loadScript(ScriptWrapper scriptWrapper) {
        try {
            return Optional.of(this.extensionScript.loadScript(scriptWrapper));
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
            return Optional.empty();
        }
    }
}
