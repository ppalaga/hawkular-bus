package org.hawkular.nest.extension;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ARCHIVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PERSISTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.URL;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hawkular.bus.broker.extension.BrokerService;
import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ServiceVerificationHandler;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.ServerEnvironmentService;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.logging.Logger;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;

/**
 * Handler responsible for adding the subsystem resource to the model
 */
class NestSubsystemAdd extends AbstractAddStepHandler {

    static final NestSubsystemAdd INSTANCE = new NestSubsystemAdd();

    private final Logger log = Logger.getLogger(NestSubsystemAdd.class);

    private NestSubsystemAdd() {
    }

    @Override
    protected void populateModel(OperationContext context, ModelNode operation, Resource resource)
            throws OperationFailedException {

        // deploy anything we find in the deployments folder
        try {
            if (requiresRuntime(context)) { // only add the steps if we are going to actually deploy now
                URL deploymentsUrl = null; // directory where the deployments should be
                Module module = Module.forClass(getClass());
                if (module != null) {
                    deploymentsUrl = module.getExportedResource(NestSubsystemExtension.DEPLOYMENTS_DIR_NAME);
                } else {
                    // if we get here, most likely we are running in a test env
                    deploymentsUrl = getClass().getClassLoader().getResource(
                            NestSubsystemExtension.DEPLOYMENTS_DIR_NAME);
                }

                if (deploymentsUrl != null) {
                    File deploymentsDir = new File(deploymentsUrl.toURI());
                    if (deploymentsDir.isDirectory()) {
                        File[] deployments = deploymentsDir.listFiles(new FilenameFilter() {
                            @Override
                            public boolean accept(File dir, String name) {
                                return name.endsWith(".ear") || name.endsWith(".war") || name.endsWith(".jar");
                            }
                        });
                        if (deployments != null) {
                            log.infof("[%d] deployments found", deployments.length);
                            int deploymentNumber = 1;
                            for (File deployment : deployments) {
                                PathAddress deploymentAddress = PathAddress.pathAddress(PathElement.pathElement(
                                        DEPLOYMENT, deployment.getName()));
                                ModelNode op = Util.getEmptyOperation(ADD, deploymentAddress.toModelNode());
                                op.get(ENABLED).set(true);
                                op.get(PERSISTENT).set(false); // prevents writing this deployment out to standalone.xml

                                ModelNode contentItem = new ModelNode();

                                if (deployment.isDirectory()) {
                                    // an exploded deployment
                                    contentItem.get(PATH).set(deployment.getAbsolutePath());
                                    contentItem.get(ARCHIVE).set(false);
                                } else {
                                    // an unexploded deployment archive
                                    contentItem.get(URL).set(deployment.toURI().toURL().toExternalForm());
                                }

                                op.get(CONTENT).add(contentItem);

                                ImmutableManagementResourceRegistration rootResourceRegistration;
                                rootResourceRegistration = context.getRootResourceRegistration();
                                OperationStepHandler handler = rootResourceRegistration.getOperationHandler(
                                        deploymentAddress, ADD);
                                context.addStep(op, handler, OperationContext.Stage.MODEL);

                                log.infof("%d. Deploying [%s]", deploymentNumber++, deployment.getName());
                            }
                        } else {
                            log.errorf("Failed to get deployments from [%s] - nothing will be deployed",
                                    NestSubsystemExtension.DEPLOYMENTS_DIR_NAME);
                        }
                    } else {
                        log.errorf("[%s] is not a directory - nothing will be deployed",
                                NestSubsystemExtension.DEPLOYMENTS_DIR_NAME);
                    }
                } else {
                    log.infof("Missing directory [%s] - nothing will be deployed",
                            NestSubsystemExtension.DEPLOYMENTS_DIR_NAME);
                }
            }
        } catch (Exception e) {
            throw new OperationFailedException("Deployments failed", e);
        }

        // finish the broker subsystem model
        populateModel(operation, resource.getModel());
    }

    @Override
    protected void populateModel(ModelNode operation, ModelNode model) throws OperationFailedException {
        NestSubsystemDefinition.AGENT_ENABLED_ATTRIBDEF.validateAndSet(operation, model);
        NestSubsystemDefinition.AGENT_NAME_ATTRIBDEF.validateAndSet(operation, model);
        NestSubsystemDefinition.CUSTOM_CONFIG_ATTRIBDEF.validateAndSet(operation, model);
        log.debug("Populating the Agent subsystem model: " + operation + "=" + model);
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model,
            ServiceVerificationHandler verificationHandler, List<ServiceController<?>> newControllers)
            throws OperationFailedException {

        boolean enabled = NestSubsystemDefinition.AGENT_ENABLED_ATTRIBDEF.resolveModelAttribute(context, model)
                .asBoolean(NestSubsystemExtension.NEST_ENABLED_DEFAULT);

        if (!enabled) {
            log.info("Nest is not enabled and will not be deployed");
            return;
        }

        log.info("Nest is enabled and will be deployed");

        // set up our runtime custom configuration properties that should be used instead of the out-of-box config
        ModelNode node = NestSubsystemDefinition.AGENT_NAME_ATTRIBDEF.resolveModelAttribute(context, model);
        String agentName = NestSubsystemExtension.NEST_NAME_DEFAULT;
        if (node.isDefined()) {
            agentName = node.asString();
        }

        // allow the user to provide their own config props
        Map<String, String> customConfigProps = new HashMap<String, String>();
        ModelNode customConfigNode = NestSubsystemDefinition.CUSTOM_CONFIG_ATTRIBDEF.resolveModelAttribute(context,
                model);
        if (customConfigNode != null && customConfigNode.isDefined()) {
            HashMap<String, String> customConfig = new HashMap<String, String>();
            List<Property> propList = customConfigNode.asPropertyList();
            for (Property prop : propList) {
                String name = prop.getName();
                String val = prop.getValue().asString();
                customConfig.put(name, val);
            }
            customConfigProps.putAll(customConfig);
        }

        // create our service
        NestService service = new NestService();
        service.setNestName(agentName);
        service.setCustomConfigurationProperties(customConfigProps);

        // install the service
        ServiceName name = NestService.SERVICE_NAME;
        ServiceController<NestService> controller = context.getServiceTarget() //
                .addService(name, service) //
                .addDependency(ServerEnvironmentService.SERVICE_NAME, ServerEnvironment.class, service.envServiceValue) //
                .addDependency(BrokerService.SERVICE_NAME, BrokerService.class, service.brokerService) //
                .addListener(verificationHandler) //
                .setInitialMode(Mode.ACTIVE) //
                .install();
        newControllers.add(controller);
        return;
    }
}
