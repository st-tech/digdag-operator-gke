package io.digdag.plugin.gke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.digdag.client.api.JacksonTimeModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;
import io.digdag.client.config.ConfigFactory;
import io.digdag.spi.CommandExecutor;
import io.digdag.spi.ImmutableTaskRequest;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorContext;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.SecretProvider;
import io.digdag.spi.SecretNotFoundException;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.standards.operator.PyOperatorFactory;
import io.digdag.standards.operator.RbOperatorFactory;
import io.digdag.standards.operator.ShOperatorFactory;
import io.digdag.util.BaseOperator;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class GkeOperatorFactory implements OperatorFactory {

    private final CommandExecutor exec;
    private final ConfigFactory cf;

    public GkeOperatorFactory(CommandExecutor exec, ConfigFactory cf) {
        this.exec = exec;
        this.cf = cf;
    }

    @Override
    public String getType() {
        return "gke";
    }

    @Override
    public Operator newOperator(OperatorContext context) {
        return new GkeOperator(this.exec, this.cf, context);
    }

    @VisibleForTesting
    class GkeOperator extends BaseOperator {

        private final CommandExecutor exec;
        private final ConfigFactory cf;
        private final OperatorContext context;
        GkeOperator(CommandExecutor exec, ConfigFactory cf, OperatorContext context) {
            super(context);
            this.context = context;
            this.exec = exec;
            this.cf = cf;
        }

        @Override
        public TaskResult runTask() {
            Config requestConfig = request.getConfig().mergeDefault(
                request.getConfig().getNestedOrGetEmpty("gke"));


            String cluster = requestConfig.get("cluster", String.class);
            String project_id = requestConfig.get("project_id", String.class);
            String zone = requestConfig.get("zone", String.class);
            String namespace = requestConfig.get("namespace", String.class, "default");

            if (requestConfig.has("credential_json") || requestConfig.has("credential_json_path") || requestConfig.has("credential_json_from_secret_key")) {
                authCLI(requestConfig);
            }

            // Auth GKECluster master with CLI
            String authGkeCommand = String.format("gcloud container clusters get-credentials %s --zone %s --project %s && kubectl get po && kubectl config set-context --current --namespace=%s", cluster, zone, project_id, namespace);
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", authGkeCommand);
            pb.inheritIO();
            try{
                final Process p = pb.start();
                p.waitFor();
            }
            catch (IOException | InterruptedException e) {
                throw Throwables.propagate(e);
            }

            TaskRequest childTaskRequest = generateChildTaskRequest(cluster, namespace, request, requestConfig);
            // gnerate OperatorContext via DefaultOperatorContext
            GkeOperatorContext internalOperatorContext = new GkeOperatorContext(
                    context.getProjectPath(),
                    childTaskRequest,
                    context.getSecrets(),
                    context.getPrivilegedVariables());

            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new GuavaModule());
            mapper.registerModule(new JacksonTimeModule());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            InjectableValues.Std injectableValues = new InjectableValues.Std();
            injectableValues.addValue(ObjectMapper.class, mapper);
            mapper.setInjectableValues(injectableValues);

            Operator operator = null;
            switch (requestConfig.get("_type", String.class)) {
                case "sh":
                    ShOperatorFactory shOperatorFactory = new ShOperatorFactory(exec);
                    operator = shOperatorFactory.newOperator(internalOperatorContext);
                    break;
                case "rb":
                    RbOperatorFactory rbOperatorFactory = new RbOperatorFactory(exec, mapper);
                    operator = rbOperatorFactory.newOperator(internalOperatorContext);
                    break;
                case "py":
                    PyOperatorFactory pyOperatorFactory = new PyOperatorFactory(exec, mapper);
                    operator = pyOperatorFactory.newOperator(internalOperatorContext);
                    break;
            }
          return operator.run();
        }

        private void authCLI(Config requestConfig) {
            String credentialJson = null;
            try {
                if (requestConfig.has("credential_json")){
                    credentialJson = requestConfig.get("credential_json", String.class).replaceAll("\n", "");
                }
                else if (requestConfig.has("credential_json_path")){
                    String credentialPath = requestConfig.get("credential_json_path", String.class);
                    credentialJson = new String(Files.readAllBytes(Paths.get(credentialPath))).replaceAll("\n", "");
                }
                else if (requestConfig.has("credential_json_from_secret_key")){
                    String credentialSecretKey = requestConfig.get("credential_json_from_secret_key", String.class);
                    credentialJson = context.getSecrets().getSecret(credentialSecretKey);
                }
            }
            catch (IOException e) {
                throw new ConfigException("Please check gcp credential file and file path.");
            }
            catch (SecretNotFoundException e) {
                throw new ConfigException(String.format("Could not access to secret:%s", requestConfig.get("credential_json_from_secret_key", String.class)));
            }

            String authCommand = String.format("echo '%s' |  gcloud auth activate-service-account --key-file=-", credentialJson);
            List<String> authCommandList = Arrays.asList("/bin/bash", "-c", authCommand);
            ProcessBuilder pb = new ProcessBuilder(authCommandList);
            pb.inheritIO();
            try {
                final Process p = pb.start();
                p.waitFor();
            }
            catch (IOException | InterruptedException e) {
                throw Throwables.propagate(e);
            }
        }

        private TaskRequest generateChildTaskRequest(String cluster, String namespace, TaskRequest parentTaskRequest, Config parentTaskRequestConfig) {
            Config commandConfig = parentTaskRequestConfig.getNestedOrGetEmpty("_command");
            Config childTaskRequestConfig = generateChildTaskRequestConfig(commandConfig);
            Config mergedChildTaskRequestConfig = parentTaskRequestConfig.merge(childTaskRequestConfig);
            Config injectedKubernetesTaskRequestConfig = injectKubernetesConfig(cluster, namespace, mergedChildTaskRequestConfig);
            return ImmutableTaskRequest.builder()
                .siteId(parentTaskRequest.getSiteId())
                .projectId(parentTaskRequest.getProjectId())
                .workflowName(parentTaskRequest.getWorkflowName())
                .revision(parentTaskRequest.getRevision())
                .taskId(parentTaskRequest.getTaskId())
                .attemptId(parentTaskRequest.getAttemptId())
                .sessionId(parentTaskRequest.getSessionId())
                .taskName(parentTaskRequest.getTaskName())
                .lockId(parentTaskRequest.getLockId())
                .timeZone(parentTaskRequest.getTimeZone())
                .sessionUuid(parentTaskRequest.getSessionUuid())
                .sessionTime(parentTaskRequest.getSessionTime())
                .createdAt(parentTaskRequest.getCreatedAt())
                .config(injectedKubernetesTaskRequestConfig)
                .localConfig(parentTaskRequest.getLocalConfig())
                .lastStateParams(parentTaskRequest.getLastStateParams())
                .build();
        }

        private Config generateChildTaskRequestConfig(Config commandConfig) {
            List<String> filterdKeys = commandConfig.getKeys()
                .stream()
                .filter((key) -> key.endsWith(">"))
                .collect(Collectors.toList());

            if (filterdKeys.size() > 1) {
                throw new ConfigException("too many operator.");
            } else if (filterdKeys.size() == 0) {
                throw new ConfigException("not found operator.");
            }

            String commandType = filterdKeys.get(0);
            if (! (commandType.equals("sh>") || commandType.equals("rb>") || commandType.equals("py>"))) {
                throw new ConfigException("GkeOperator support only sh>, rb> and py>.");
            }

            commandConfig.set("_command", commandConfig.get(commandType, String.class));
            // exclude > (end of commandType String).
            commandConfig.set("_type", commandType.substring(0, commandType.length()-1));
            if (commandConfig.has("_export")) {
                commandConfig.merge(commandConfig.getNested("_export"));
            }
            return commandConfig;
        }

        @VisibleForTesting
        Config injectKubernetesConfig(String cluster, String namespace, Config taskRequestConfig){
            // set information for kubernetes command executor.
            String kubeConfigPath = System.getenv("KUBECONFIG");
            if (kubeConfigPath == null) {
                kubeConfigPath = Paths.get(System.getenv("HOME"), ".kube/config").toString();
            }

            io.fabric8.kubernetes.client.Config kubeConfig = getKubeConfigFromPath(kubeConfigPath);
            if (taskRequestConfig.has("kubernetes")) {
                Config kubenretesConfig = taskRequestConfig.getNestedOrGetEmpty("kubernetes");
                kubenretesConfig.set("name", cluster);
                kubenretesConfig.set("master", kubeConfig.getMasterUrl());
                kubenretesConfig.set("certs_ca_data", kubeConfig.getCaCertData());
                kubenretesConfig.set("oauth_token", kubeConfig.getOauthToken());
                kubenretesConfig.set("namespace", namespace);
                taskRequestConfig.set("kubernetes", kubenretesConfig);
            } else {
                taskRequestConfig.set("kubernetes", cf.create()
                    .set("name", cluster)
                    .set("master", kubeConfig.getMasterUrl())
                    .set("certs_ca_data", kubeConfig.getCaCertData())
                    .set("oauth_token", kubeConfig.getOauthToken())
                    .set("namespace", namespace));
            }
            return taskRequestConfig;
        }

        io.fabric8.kubernetes.client.Config getKubeConfigFromPath(String path)
        {
            try {
                final Path kubeConfigPath = Paths.get(path);
                final String kubeConfigContents = new String(Files.readAllBytes(kubeConfigPath), Charset.forName("UTF-8"));
                return io.fabric8.kubernetes.client.Config.fromKubeconfig(kubeConfigContents);
            } catch (java.io.IOException e) {
                throw new ConfigException("Could not read kubeConfig, check kube_config_path.");
            }
        }
    }
}
